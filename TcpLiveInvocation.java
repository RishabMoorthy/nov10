package org.framework.core;

import com.stubio.util.ExecutionMode;

import org.common.db.config.ConfigLoader;
import org.framework.config.ServiceConfig;
import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.services.tcp.TcpFrameSpec;
import org.framework.services.tcp.TcpMessageFramer;
import org.framework.utils.IoBufferPipeAdapter;
import org.framework.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Live Invocation and Failover for TCP services - the TCP counterpart of {@link LiveInvocation}.
 *
 * <p>Sends the request bytes exactly as received to the active LiveURL (host + port; TransType
 * and BasePath play no part) and reads one reply, framed with the same six TCP Handler fields
 * as the requests. When the Response Handler script is filled in, it frames the reply instead,
 * with the same bindings as the request handler script.
 *
 * <p>Outcomes, matching the HTTP behaviour:
 * <ul>
 *   <li>The server replied - that reply is the answer, in both modes.</li>
 *   <li>The call failed, Live Invocation - the caller gets the partial reply if any bytes
 *       arrived, otherwise the error text {@code Error invoking live service: ...}.</li>
 *   <li>The call failed, Failover - nothing is returned; the caller serves the mock response.
 *       One attempt only.</li>
 * </ul>
 *
 * <p>Timeouts are the same config.properties keys as HTTP: {@code executionmode.live.*} and
 * {@code executionmode.failover.*} ({@code connectTimeoutSeconds}, {@code requestTimeoutSeconds}).
 */
public class TcpLiveInvocation {

    private static final int DEFAULT_LIVE_CONNECT_SECONDS = 30;
    private static final int DEFAULT_LIVE_REQUEST_SECONDS = 120;
    private static final int DEFAULT_FAILOVER_CONNECT_SECONDS = 10;
    private static final int DEFAULT_FAILOVER_REQUEST_SECONDS = 30;

    public LiveInvocation.LiveResult invoke(Context context, MockRequest request, TcpFrameSpec spec,
            boolean failoverMode) {

        ServiceConfig config = context.getMockService().getConfig();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        String target = null;

        try {
            ExecutionMode.LiveURL url = activeLiveUrl(config);
            if (url == null || trim(url.getHost()).isEmpty() || trim(url.getPort()).isEmpty()) {
                throw new IllegalStateException("no active Live URL with Host and Port");
            }
            String host = trim(url.getHost());
            int port = Integer.parseInt(trim(url.getPort()));
            target = host + ":" + port;

            int connectMs = 1000 * (failoverMode
                    ? seconds("executionmode.failover.connectTimeoutSeconds", DEFAULT_FAILOVER_CONNECT_SECONDS)
                    : seconds("executionmode.live.connectTimeoutSeconds", DEFAULT_LIVE_CONNECT_SECONDS));
            int readMs = 1000 * (failoverMode
                    ? seconds("executionmode.failover.requestTimeoutSeconds", DEFAULT_FAILOVER_REQUEST_SECONDS)
                    : seconds("executionmode.live.requestTimeoutSeconds", DEFAULT_LIVE_REQUEST_SECONDS));

            byte[] payload = request.getRequestContent() == null
                    ? new byte[0]
                    : request.getRequestContent().getBytes(TcpFrameSpec.CHARSET);

            Logger.getInstance().info("[TCP Live] " + config.getServiceName() + " -> " + target
                    + " sending " + payload.length + " bytes");
            long start = System.currentTimeMillis();

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), connectMs);
                socket.setSoTimeout(readMs);
                socket.setTcpNoDelay(true);

                OutputStream os = socket.getOutputStream();
                os.write(payload);
                os.flush();

                byte[] reply = readReply(socket.getInputStream(), spec, context, received);

                Logger.getInstance().info("[TCP Live] " + config.getServiceName() + " <- " + target
                        + " " + reply.length + " bytes in " + (System.currentTimeMillis() - start) + " ms");
                return LiveInvocation.LiveResult.answered(liveResponse(reply, request, config));
            }

        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());

            if (failoverMode) {
                Logger.getInstance().warn("[TCP Live] call failed" + (target == null ? "" : " for " + target)
                        + " (" + reason + ") - Failover will serve the mock response.");
                return LiveInvocation.LiveResult.failed(null, reason);
            }

            Logger.getInstance().error("[TCP Live] call failed" + (target == null ? "" : " for " + target), e);
            if (received.size() > 0) {
                Logger.getInstance().info("[TCP Live] returning partial reply, bytes received: " + received.size());
                return LiveInvocation.LiveResult.failed(liveResponse(received.toByteArray(), request, config), reason);
            }
            byte[] error = ("Error invoking live service: " + reason).getBytes(TcpFrameSpec.CHARSET);
            return LiveInvocation.LiveResult.failed(liveResponse(error, request, config), reason);
        }
    }

    /**
     * Reads one complete reply. {@code received} collects every byte read, so a failure part-way
     * can still report what arrived.
     */
    private byte[] readReply(InputStream in, TcpFrameSpec spec, Context context, ByteArrayOutputStream received)
            throws Exception {

        byte[] chunk = new byte[8192];

        if (spec.hasResponseScript()) {
            TcpMessageFramer framer = new TcpMessageFramer(spec, spec.getResponseScript());
            IoBufferPipeAdapter pipe = new IoBufferPipeAdapter();
            while (true) {
                int n = in.read(chunk);
                if (n == -1) {
                    throw new java.io.EOFException("connection closed before the reply was complete");
                }
                received.write(chunk, 0, n);
                pipe.append(chunk, 0, n);
                TcpMessageFramer.Frame frame = framer.next(pipe, new Context(context));
                if (frame != null) {
                    return frame.getContent().getBytes(TcpFrameSpec.CHARSET);
                }
            }
        }

        if (!spec.isFramed()) {
            // No framing configured: take the first chunk plus whatever is already waiting.
            int n = in.read(chunk);
            if (n == -1) {
                throw new java.io.EOFException("connection closed without a reply");
            }
            received.write(chunk, 0, n);
            while (in.available() > 0 && (n = in.read(chunk)) != -1) {
                received.write(chunk, 0, n);
            }
            return received.toByteArray();
        }

        while (true) {
            byte[] sofar = received.toByteArray();
            int total = spec.completeLength(sofar, sofar.length);
            if (total >= 0) {
                // Anything past one reply is not ours - send exactly one message back.
                byte[] reply = new byte[total];
                System.arraycopy(sofar, 0, reply, 0, total);
                return reply;
            }
            int n = in.read(chunk);
            if (n == -1) {
                throw new java.io.EOFException("connection closed before the reply was complete ("
                        + sofar.length + " bytes received)");
            }
            received.write(chunk, 0, n);
        }
    }

    private MockResponse liveResponse(byte[] bytes, MockRequest request, ServiceConfig config) {
        MockResponse response = new MockResponse(LiveInvocation.LIVE_RESPONSE_NAME, bytes, null,
                request.getOperation(), config.getServiceName());
        response.setStatusCode(200);
        response.setSource("live");
        return response;
    }

    /** The active LiveURL, else the first one. */
    private static ExecutionMode.LiveURL activeLiveUrl(ServiceConfig config) {
        ExecutionMode mode = config.getExecutionModeModel();
        if (mode == null || mode.getLiveURLs() == null || mode.getLiveURLs().isEmpty()) {
            return null;
        }
        for (ExecutionMode.LiveURL url : mode.getLiveURLs()) {
            if (url.isActive()) {
                return url;
            }
        }
        return mode.getLiveURLs().get(0);
    }

    private static int seconds(String key, int fallback) {
        try {
            String raw = ConfigLoader.getProperty(key);
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
