package org.framework.services.tcp;

import org.common.db.config.ConfigLoader;
import org.framework.config.ServiceConfig;
import org.framework.core.*;
import org.framework.core.impl.GroovyScriptExecutor;
import org.framework.db.Utility;
import org.framework.properties.*;
import org.framework.utils.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * - Production-ready, non-blocking TCP service using NIO with worker pool and
 * robust write handling.
 * - Features:
 * - - Non-blocking I/O with proper selector management
 * - - Worker thread pool for request processing
 * - - Race-condition-free write handling
 * - - Connection timeout management
 * - - Graceful shutdown
 * - - Comprehensive error handling
 */
public class TCPService extends AbstractService {
    private boolean isRunning;
    // Core NIO components
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int port;
    private Context globalContext;
    private ScheduledExecutorService metricsReporter;
    // Thread pools
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService timeoutChecker;
    private final AtomicLong processingCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private static final int MAX_PIPE_SIZE = 64 * 1024; // 64KB max per connection
    private static final int MAX_WRITE_QUEUE_SIZE = 10; // Prevent queue buildup
    // Session management
    private final ConcurrentHashMap<SelectionKey, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> pendingSelectorTasks = new ConcurrentLinkedQueue<>();

    // Configuration
    private String timestamp;
    public ServiceRespTimeHandler respTimeHandler = new ServiceRespTimeHandler();

    // Metrics
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);

    // Constants
    private static final int READ_BUFFER_SIZE = 8 * 1024; // 8KB
    private static final int WRITE_BUFFER_SIZE = 16 * 1024; // 16KB
    private static final long CONNECTION_TIMEOUT_MS = 300_000; // 5 minutes
    private static final long SELECTOR_TIMEOUT_MS = 1000; // 1 second

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }

    /**
     * - Session holds per-connection state with thread-safe write queue
     */
    private static class Session {
        final SocketChannel channel;
        final SelectionKey key;
        final IoBufferPipeAdapter pipe;
        final Deque<ByteBuffer> writeQueue;
        final AtomicBoolean writing;
        final ByteBuffer readBuffer;
        volatile long lastActivity;
        volatile boolean closed;
        final AtomicBoolean processing;
        volatile long requestStartTime;
        final AtomicLong requestCount;

        Session(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
            this.pipe = new IoBufferPipeAdapter();
            this.writeQueue = new ConcurrentLinkedDeque<>();
            this.writing = new AtomicBoolean(false);
            this.readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            this.lastActivity = System.currentTimeMillis();
            this.closed = false;
            this.processing = new AtomicBoolean(false);
            this.requestCount = new AtomicLong(0);
            this.requestStartTime = 0;
        }

        void updateActivity() {
            this.lastActivity = System.currentTimeMillis();
        }

        boolean isTimedOut(long timeoutMs) {
            return System.currentTimeMillis() - lastActivity > timeoutMs;
        }

        boolean isPipeOverflow() {
            return pipe.size() > MAX_PIPE_SIZE;
        }

        boolean isWriteQueueFull() {
            return writeQueue.size() >= MAX_WRITE_QUEUE_SIZE;
        }
    }

    /** Cuts complete messages out of the received bytes - built-in or handler script. */
    private TcpMessageFramer framer;

    /**
     * Legacy ReadyAPI services kept their delimiter script in this property. It is used only
     * when the TCP Handler request script is empty.
     */
    private static final String LEGACY_DELIMITER_PROPERTY = "tcpMockService.requestDelimiterParams";

    public TCPService(ServiceConfig config) {
        super(config);
        this.port = config.getPort();
        this.globalContext = new Context(this);
    }

    private TcpMessageFramer buildFramer() {
        TcpFrameSpec spec = config.getTcpFrameSpec();
        String script = spec.getRequestScript();
        if (script.isEmpty() && config.getProperties() != null && config.getProperties().getProperties() != null) {
            String legacy = config.getProperties().getProperties().get(LEGACY_DELIMITER_PROPERTY);
            if (legacy != null && !legacy.isBlank()) {
                script = legacy;
            }
        }
        return new TcpMessageFramer(spec, script);
    }

    @Override
    public String getTimestamp() {
        return timestamp;
    }

    @Override
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Service already running");
        }

        try {
            // Framing from the TCP Handler fields. A bad field value fails the start here.
            this.framer = buildFramer();
            Logger.getInstance().info("[TCP] " + config.getServiceName() + " framing: " + config.getTcpFrameSpec()
                    + (framer.usesScript() ? " (request handler script)" : " (built-in)"));

            // Thread pool: <vs:Config><vs:ThreadPool coreThreads maxThreads> when set,
            // otherwise sized from the CPU count as before.
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int workerThreads = Math.max(4, availableProcessors * 2);
            int coreThreads = workerThreads;
            int maxThreads = workerThreads * 2;
            if (config.getVirtualService() != null && config.getVirtualService().getCoreThreads() > 0) {
                coreThreads = config.getVirtualService().getCoreThreads();
                maxThreads = Math.max(coreThreads, coreThreads * 2);
            }
            if (config.getVirtualService() != null && config.getVirtualService().getMaxThreads() > 0) {
                maxThreads = Math.max(coreThreads, config.getVirtualService().getMaxThreads());
            }

            // Worker pool with bounded queue and rejection policy
            this.workers = new ThreadPoolExecutor(
                    coreThreads,
                    maxThreads,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(1000),
                    new ThreadFactory() {
                        private final AtomicLong counter = new AtomicLong(0);

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "tcp-worker-" + counter.incrementAndGet());
                            t.setDaemon(false);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure
            );

            // Scheduler for delayed responses
            this.scheduler = Executors.newScheduledThreadPool(
                    4,
                    r -> {
                        Thread t = new Thread(r, "tcp-scheduler");
                        t.setDaemon(true);
                        return t;
                    });

            // Timeout checker
            this.timeoutChecker = Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "tcp-timeout-checker");
                        t.setDaemon(true);
                        return t;
                    });

            this.metricsReporter = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tcp-metrics");
                t.setDaemon(true);
                return t;
            });
            // Initialize selector and server channel
            globalContext = new Context(this);
            Utility.getInstance().syncExecutionMode(config);
            selector = Selector.open();

            serverChannel = ServerSocketChannel.open();
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port), 1024);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // Initialize scripts
            normalizeScripts(config);

            // Start response time handler
            // respTimeHandler.start();
            runStartScript(globalContext, config);
            // Start timeout checker
            timeoutChecker.scheduleAtFixedRate(
                    this::checkTimeouts,
                    30, 30, TimeUnit.SECONDS);

            metricsReporter.scheduleAtFixedRate(
                    this::reportMetrics,
                    10, 10, TimeUnit.SECONDS);

            // Start event loop in separate thread
            Thread eventThread = new Thread(this::eventLoop, "tcp-selector");
            eventThread.setDaemon(true);
            Utility.getInstance().setCustomDelayConfig(config, CustomMethods.getLocalHostAddress());
            eventThread.start();
            isRunning = true;
            System.out.println("NIO TCP Server started on port " + port);

        } catch (Exception e) {
            running.set(false);
            cleanup();
            throw new IOException("Failed to start TCP service", e);
        }
    }

    /**
     * - Main event loop - runs selector operations
     */
    private void eventLoop() {
        while (running.get()) {
            try {
                // Process pending selector tasks (e.g., interest ops changes)
                processPendingSelectorTasks();

                // Select with timeout
                int readyCount = selector.select(SELECTOR_TIMEOUT_MS);

                if (readyCount == 0) {
                    continue;
                }

                // Process ready keys
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        System.err.println("Error handling key: " + e.getMessage());
                        e.printStackTrace();
                        closeSession(key);
                    }
                }

            } catch (ClosedSelectorException e) {
                System.out.println("Selector closed, stopping event loop");
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("Selector error: " + e.getMessage());
                    e.printStackTrace();
                }
            } catch (Exception e) {
                System.err.println("Unexpected error in event loop: " + e.getMessage());
                e.printStackTrace();
            }

        }
    }

    /**
     * - Handle new client connections
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel == null) {
            return;
        }

        try {
            if ("Yes".equals(RespTimeConfigManager.getConfig().get(config.getServiceName())))
                respTimeHandler.start();
            clientChannel.configureBlocking(false);
            clientChannel.socket().setTcpNoDelay(true);
            clientChannel.socket().setKeepAlive(true);
            clientChannel.socket().setSoLinger(false, 0); // FIXED: Disable SO_LINGER
            clientChannel.socket().setReceiveBufferSize(READ_BUFFER_SIZE); // FIXED: Set buffer size
            clientChannel.socket().setSendBufferSize(WRITE_BUFFER_SIZE);

            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            Session session = new Session(clientChannel, clientKey);
            sessions.put(clientKey, session);
            clientKey.attach(session);

            activeConnections.incrementAndGet();

            System.out.println("Accepted connection from " +
                    clientChannel.getRemoteAddress() +
                    " (active: " + activeConnections.get() + ")");

        } catch (IOException e) {
            System.err.println("Error accepting connection: " + e.getMessage());
            try {
                clientChannel.close();
            } catch (IOException closeEx) {
                // Ignore
            }
        }
    }

    /**
     * - Handle read events - reads data and submits to worker pool
     */
    private void handleRead(SelectionKey key) {
        Session session = (Session) key.attachment();
        if (session == null || session.closed) {
            return;
        }

        // Check for overflow BEFORE reading
        if (session.isPipeOverflow()) {
            System.err.println("Pipe overflow for session, closing connection");
            closeSession(key);
            return;
        }

        try {
            session.updateActivity();
            ByteBuffer buffer = session.readBuffer;
            buffer.clear();

            int bytesRead = session.channel.read(buffer);

            if (bytesRead == -1) {
                // Client closed connection
                closeSession(key);
                return;
            }

            if (bytesRead == 0) {
                return;
            }

            // Transfer data to pipe
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            session.pipe.append(data, 0, data.length);

            if (!session.processing.get()) {
                processRequest(session);
            }

        } catch (IOException e) {
            System.err.println("Error reading from client: " + e.getMessage());
            closeSession(key);
        }
    }

    /**
     * - Process accumulated data in session pipe
     */
    private void processRequest(Session session) {

        // FIXED: Check if already processing
        if (!session.processing.compareAndSet(false, true)) {
            return; // Already processing a request
        }
        session.requestStartTime = System.currentTimeMillis();
        processingCount.incrementAndGet();
        try {
            // Create request objects
            TcpRequest request = new TcpRequest();
            MockRequest mockRequest = new MockRequest();
            Context context = new Context(globalContext);

            // Cut one complete message out of the bytes received so far. Only that message is
            // removed from the pipe - bytes of the next message stay for the next round.
            TcpMessageFramer.Frame frame = framer.next(session.pipe, context);

            if (frame == null) {
                // Incomplete request, reset processing flag and wait for more data
                session.processing.set(false);
                processingCount.decrementAndGet();
                return;
            }

            mockRequest.setRequestContent(frame.getContent());
            mockRequest.setOperation(frame.getOperation());
            mockRequest.setProtocol("TCP");
            exposeTlv(context, frame.getContent());
            Logger.getInstance().info("[TCP] " + config.getServiceName() + " request (" + frame.getContent().length()
                    + " bytes): " + frame.getContent());

            RequestTracker.incrementRequest(context.getMockService().getConfig().getServiceName());
            RequestTrackerDB.incrementRequest(context.getMockService().getConfig().getServiceName());

            // Submit to worker pool
            totalRequests.incrementAndGet();
            session.requestCount.incrementAndGet();

            try {
                workers.submit(() -> processRequestOnWorker(session, mockRequest, context, request));
            } catch (RejectedExecutionException e) {
                System.err.println("Worker pool full, sending 503");
                errorCount.incrementAndGet();
                sendErrorResponse(session, "503 Service Unavailable");
                processingCount.decrementAndGet();
            }

        } catch (Exception e) {
            Logger.getInstance().error("[TCP] " + config.getServiceName() + " cannot read request, closing connection: "
                    + e.getMessage());
            errorCount.incrementAndGet();
            session.processing.set(false);
            processingCount.decrementAndGet();
            closeSession(session.key);
        }
    }

    /**
     * Puts the request's TLV fields where scripts and templates can reach them:
     * {@code context.tlv} (a map, e.g. {@code context.tlv["T02"]}) and {@code tlv.<TAG>}
     * (e.g. {@code ${tlv.T02}} in a response or a data source query).
     */
    private void exposeTlv(Context context, String message) {
        try {
            TcpFrameSpec spec = config.getTcpFrameSpec();
            Map<String, String> tlv = spec.parseTlv(spec.bodyOf(message));
            context.getDynamicProperties().put("tlv", tlv);
            for (Map.Entry<String, String> field : tlv.entrySet()) {
                context.getDynamicProperties().put("tlv." + field.getKey(), field.getValue());
            }
        } catch (Exception e) {
            Logger.getInstance().warn("[TCP] could not read TLV fields: " + e.getMessage());
        }
    }

    /**
     * - Process request on worker thread
     */
    private void processRequestOnWorker(Session session, MockRequest mockRequest,
            Context context, TcpRequest request) {
        try {
            if (session.closed) {
                session.processing.set(false);
                processingCount.decrementAndGet();
                return;
            }
            // Execute main script to generate response
            runOnRequestScript(context, mockRequest);

            // On Request custom script. A failing logging script must not stop the reply - a TCP
            // client waiting for a framed message cannot use a "500" text.
            try {
                GroovyScriptExecutor executor = new GroovyScriptExecutor();
                HashMap<String, Object> props = new HashMap<>();
                props.put("context", context);
                props.put("mockRequest", mockRequest);
                executor.executeScript(config.getOnRequestScript(), props, context);
            } catch (Exception e) {
                Logger.getInstance().error("[TCP] " + config.getServiceName() + " On Request script failed: "
                        + e.getMessage());
            }

            // Execute response handling
            MockResponse response = request.executeMainScript(mockRequest, context, config);
            byte[] responseBytes = response.getResponseBytes();
            if (responseBytes == null) {
                responseBytes = new byte[0];
            }
            final byte[] finalResponseBytes = responseBytes;
            Logger.getInstance().info("[TCP] " + config.getServiceName() + " response '" + response.getName() + "' ("
                    + responseBytes.length + " bytes): " + new String(responseBytes, TcpFrameSpec.CHARSET));

            // Delayed response: the scheduler waits, the worker does not. (It used to sleep
            // here AND schedule, which applied the delay twice.)
            int delayMs = CustomMethods.getInstance().calculateDelay(config);
            Logger.getInstance().info("delay " + delayMs);

            if (delayMs > 0) {
                // CRITICAL FIX: Wrap in try-catch and always reset processing flag
                scheduler.schedule(() -> {
                    try {
                        sendResponse(session, finalResponseBytes, mockRequest, response, context);
                    } catch (Exception e) {
                        System.err.println("Error in delayed response: " + e.getMessage());
                        errorCount.incrementAndGet();
                        session.processing.set(false);
                        processingCount.decrementAndGet();
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                sendResponse(session, responseBytes, mockRequest, response, context);
            }

            RequestLog requestlog = new RequestLog(mockRequest, response, "",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), config.getServiceName(),
                    config.getPort(), isRunning, config.getType());
            RequestTracker.logRequest(config.getServiceName(), requestlog);
            // CHECK IF NOT SANITY

            RequestTrackerDB.logRequest(config.getServiceName(), requestlog);
            ReqResLogger reqRes = new ReqResLogger();
            reqRes.log(config.getServiceName(), requestlog);

        } catch (Exception e) {
            System.err.println("Error in worker processing: " + e.getMessage());
            e.printStackTrace();
            errorCount.incrementAndGet();
            sendErrorResponse(session, "500 Internal Server Error\r\n");
            session.processing.set(false);
            processingCount.decrementAndGet();
        }
    }

    /**
     * - Send response by queuing to write queue and enabling OP_WRITE
     */
    private void sendResponse(Session session, byte[] response,
            MockRequest mockRequest, MockResponse mockResponse, Context context) {
        if (session.closed) {
            session.processing.set(false);
            processingCount.decrementAndGet();
            return;
        }

        // Check write queue size
        if (session.isWriteQueueFull()) {
            System.err.println("Write queue full for session, dropping response");
            errorCount.incrementAndGet();
            session.processing.set(false);
            processingCount.decrementAndGet();
            closeSession(session.key);
            return;
        }

        try {
            // Log request/response
            // logRequest(mockRequest, mockResponse, context);

            // Wrap response in ByteBuffer
            ByteBuffer buffer = ByteBuffer.allocate(response.length);
            buffer.put(response);
            buffer.flip();

            // Add to write queue
            session.writeQueue.offer(buffer);

            // Enable OP_WRITE interest in selector thread
            enqueueSelectorTask(() -> {
                try {
                    if (session.key.isValid() && !session.closed) {
                        session.key.interestOps(session.key.interestOps() | SelectionKey.OP_WRITE);
                        selector.wakeup();
                    }
                } catch (Exception e) {
                    System.err.println("Error enabling write: " + e.getMessage());
                } finally {
                    // CRITICAL: Always reset processing flag
                    session.processing.set(false);
                    processingCount.decrementAndGet();
                }
            });

            afterRequestScriptHandler(context, mockRequest, mockResponse);

            long processingTime = System.currentTimeMillis() - session.requestStartTime;
            if (processingTime > 1000) {
                System.out.println("Slow request: " + processingTime + "ms");
            }

        } catch (Exception e) {
            System.err.println("Error sending response: " + e.getMessage());
            e.printStackTrace();
            errorCount.incrementAndGet();
            session.processing.set(false);
            processingCount.decrementAndGet();
        }
    }

    /**
     * - Send error response
     */
    private void sendErrorResponse(Session session, String errorMessage) {
        if (session.closed) {
            return;
        }
        byte[] errorBytes = errorMessage.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(errorBytes.length);
        buffer.put(errorBytes);
        buffer.flip();

        session.writeQueue.offer(buffer);

        enqueueSelectorTask(() -> {
            try {
                if (session.key.isValid() && !session.closed) {
                    session.key.interestOps(session.key.interestOps() | SelectionKey.OP_WRITE);
                    selector.wakeup();
                }
            } finally {
                session.processing.set(false); // FIXED: Always reset
                processingCount.decrementAndGet();
            }
        });
    }

    /**
     * - Handle write events - write data from queue to socket
     * - This is the critical section that must be race-condition free
     */
    private void handleWrite(SelectionKey key) {
        Session session = (Session) key.attachment();
        if (session == null || session.closed) {
            return;
        }

        try {
            session.updateActivity();

            // Process write queue
            while (true) {
                ByteBuffer buffer = session.writeQueue.peek();

                if (buffer == null) {
                    // FIXED: Safely disable OP_WRITE
                    try {
                        int ops = key.interestOps();
                        key.interestOps(ops & ~SelectionKey.OP_WRITE);
                    } catch (CancelledKeyException e) {
                        // Key cancelled during write
                        closeSession(key);
                        return;
                    }
                    // After write queue is empty, can process new request
                    if (!session.processing.get() && session.pipe.size() > 0) {
                        processRequest(session);
                    }
                    break;
                }

                // Write as much as possible
                int written = session.channel.write(buffer);

                if (!buffer.hasRemaining()) {
                    // Buffer fully written, remove from queue
                    session.writeQueue.poll();
                } else {

                    // Socket buffer full, will retry on next OP_WRITE event
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Error writing to client: " + e.getMessage());
            errorCount.incrementAndGet();
            closeSession(key);
        } catch (CancelledKeyException e) {
            // Key was cancelled, close session
            closeSession(key);
        }
    }

    /**
     * - Enqueue task to be run in selector thread
     * - This ensures thread-safe selector operations
     */
    private void enqueueSelectorTask(Runnable task) {
        pendingSelectorTasks.offer(task);
        if (selector != null) { // FIXED: Null check
            selector.wakeup();
        }
    }

    /**
     * - Process tasks that need to run in selector thread
     */
    private void processPendingSelectorTasks() {
        int processed = 0;
        Runnable task;
        while ((task = pendingSelectorTasks.poll()) != null && processed < 100) {
            try {
                task.run();
                processed++;
            } catch (Exception e) {
                System.err.println("Error executing selector task: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (processed >= 100) {
            System.err.println("WARNING: Selector task queue backed up, processed 100+");
        }
    }

    /**
     * - Close session and cleanup resources
     */
    private void closeSession(SelectionKey key) {
        Session session = sessions.remove(key);
        if (session == null) {
            return;
        }

        if (session.closed) {
            return;
        }

        session.closed = true;
        // Clean up processing state
        if (session.processing.get()) {
            session.processing.set(false);
            processingCount.decrementAndGet();
        }

        try {
            key.cancel();
        } catch (Exception e) {
            // Ignore
        }

        try {
            session.channel.close();
        } catch (IOException e) {
            System.err.println("Error closing channel: " + e.getMessage());
        }

        activeConnections.decrementAndGet();
        System.out.println("Connection closed (active: " + activeConnections.get() + ")");
    }

    /**
     * - Check for timed out connections
     */
    private void checkTimeouts() {
        try {
            List<SelectionKey> timedOut = new ArrayList<>();
            long now = System.currentTimeMillis();

            for (Map.Entry<SelectionKey, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                if (session.isTimedOut(CONNECTION_TIMEOUT_MS)) {
                    timedOut.add(entry.getKey());
                }

                // Check for stuck processing
                if (session.processing.get() && session.requestStartTime > 0) {
                    long processingTime = now - session.requestStartTime;
                    if (processingTime > 60000) { // 60 seconds
                        System.err.println("Stuck request detected: " + processingTime + "ms");
                        timedOut.add(entry.getKey());
                    }
                }
            }

            if (!timedOut.isEmpty()) {
                System.out.println("Closing " + timedOut.size() + " timed-out connections");
                for (SelectionKey key : timedOut) {
                    closeSession(key);
                }
            }

        } catch (Exception e) {
            System.err.println("Error checking timeouts: " + e.getMessage());
        }
    }

    private void reportMetrics() {
        try {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) workers;
            /*
             * System.out.println(String.format(
             * "=== METRICS === Active: %d, Processing: %d, Total Reqs: %d, Errors: %d, " +
             * "Worker Pool: %d/%d (queue: %d), Selector Tasks: %d, Sessions: %d",
             * activeConnections.get(),
             * processingCount.get(),
             * totalRequests.get(),
             * errorCount.get(),
             * tpe.getActiveCount(),
             * tpe.getPoolSize(),
             * tpe.getQueue().size(),
             * pendingSelectorTasks.size(),
             * sessions.size()
             * ));
             */
        } catch (Exception e) {
            System.err.println("Error reporting metrics: " + e.getMessage());
        }
    }

    /**
     * - Log request for monitoring
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        isRunning = false;

        System.out.println("Stopping TCP service...");

        try {
            // Close all sessions
            for (SelectionKey key : new ArrayList<>(sessions.keySet())) {
                closeSession(key);
            }

            cleanup();

            System.out.println("TCP service stopped");

        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * - Cleanup resources
     */
    private void cleanup() {
        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing selector: " + e.getMessage());
        }

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server channel: " + e.getMessage());
        }

        workers.shutdown();
        scheduler.shutdown();
        timeoutChecker.shutdown();
        metricsReporter.shutdown();

        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!timeoutChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutChecker.shutdownNow();
            }
            if (!metricsReporter.awaitTermination(2, TimeUnit.SECONDS)) {
                metricsReporter.shutdownNow();
            }

        } catch (InterruptedException e) {
            workers.shutdownNow();
            scheduler.shutdownNow();
            timeoutChecker.shutdownNow();
            metricsReporter.shutdown();
            Thread.currentThread().interrupt();
        }

        try {
            runStopScript(globalContext);
        } catch (Exception e) {
            System.err.println("Error running stop script: " + e.getMessage());
        }
    }

    private void normalizeScripts(ServiceConfig config) {
        if (config.getStartScript() != null && !config.getStartScript().isEmpty()) {
            config.setStartScript(config.getStartScript().replaceAll(
                    "com\\.vs\\.met\\.(\\*|VSMetricstoDB)",
                    "org.framework.utils.VSMetricstoDB"));
        }
        if (config.getOnRequestScript() != null && !config.getOnRequestScript().isEmpty()) {
            config.setOnRequestScript(config.getOnRequestScript().replaceAll(
                    "com\\.vs\\.met\\.(\\*|VSMetricstoDB)",
                    "org.framework.utils.VSMetricstoDB"));
        }
        if (config.getAfterRequestScript() != null && !config.getAfterRequestScript().isEmpty()) {
            config.setAfterRequestScript(config.getAfterRequestScript().replaceAll(
                    "com\\.vs\\.met\\.(\\*|VSMetricsRespToDB)",
                    "org.framework.utils.VSMetricsRespToDB"));
        }
        if (config.getStopScript() != null && !config.getStopScript().isEmpty()) {
            config.setStopScript(config.getStopScript().replaceAll(
                    "com\\.vs\\.met\\.(\\*|VSMetricstoDB)",
                    "org.framework.utils.VSMetricstoDB"));
        }
    }

    public void runStartScript(Context globalContext, ServiceConfig config) {
        if (config.getStartScript() == null || config.getStartScript().isEmpty()) {
            return;
        }

        GroovyScriptExecutor executor = new GroovyScriptExecutor();
        HashMap<String, Object> props = new HashMap<>();
        props.put("context", globalContext);
        executor.executeScript(config.getStartScript(), props, globalContext);
    }

    public void runOnRequestScript(Context context, MockRequest request) {
        if (config.getOnRequestScript() == null || config.getOnRequestScript().isEmpty()) {
            return;
        }

        String shouldRun = RespTimeConfigManager.getConfig().get(config.getServiceName());
        if (!"Yes".equals(shouldRun)) {
            return;
        }

        respTimeHandler.onRequest(context);
    }

    /**
     * The On Response custom script - runs after every response, as for REST. Response-time
     * capture still runs only when it is switched on for the service.
     */
    public void afterRequestScriptHandler(Context context, MockRequest request, MockResponse response) {
        if (config.getAfterRequestScript() != null && !config.getAfterRequestScript().isEmpty()) {
            try {
                GroovyScriptExecutor executor = new GroovyScriptExecutor();
                HashMap<String, Object> props = new HashMap<>();
                props.put("context", context);
                props.put("mockRequest", request);
                props.put("mockResult", new MockResult(request, response));
                executor.executeScript(config.getAfterRequestScript(), props, context);
            } catch (Exception e) {
                Logger.getInstance().error("[TCP] " + config.getServiceName() + " On Response script failed: "
                        + e.getMessage());
            }
        }

        if ("Yes".equals(RespTimeConfigManager.getConfig().get(config.getServiceName()))) {
            respTimeHandler.afterRequest(context);
        }
    }

    public void runStopScript(Context globalContext) {
        if (config.getStopScript() == null || config.getStopScript().isEmpty()) {
            return;
        }

        GroovyScriptExecutor executor = new GroovyScriptExecutor();
        HashMap<String, Object> props = new HashMap<>();
        props.put("context", globalContext);
        executor.executeScript(config.getStopScript(), props, globalContext);
    }

    // Metrics and monitoring
    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    static class TcpRequest {
        // Framing (the old executeRequestDelimiter) moved to TcpMessageFramer, which also
        // runs a ReadyAPI-style delimiter script when one is configured.

        public MockResponse executeMainScript(MockRequest request, Context context, ServiceConfig config)
                throws Exception {
            return config.getRequestHandler().handleRequest(context, request);
        }
    }
}

