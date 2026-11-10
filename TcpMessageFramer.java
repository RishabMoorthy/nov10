package org.framework.services.tcp;

import org.framework.core.impl.GroovyScriptExecutor;
import org.framework.properties.Context;
import org.framework.properties.Out;
import org.framework.utils.IoBufferPipeAdapter;
import org.framework.utils.Logger;

import java.util.HashMap;

/**
 * Cuts complete messages out of the bytes received on a TCP connection.
 *
 * <p>Two ways, picked per service:
 * <ul>
 *   <li><b>Built-in</b> (handler script empty) - uses the six TCP Handler fields
 *       ({@link TcpFrameSpec}). Nothing to write; this is what TIBCO uses.</li>
 *   <li><b>Script</b> (handler script filled in) - runs the script with the same bindings the
 *       ReadyAPI request delimiter had: {@code buff}, {@code out}, {@code context}, {@code log},
 *       {@code globalProperties}, plus the six fields as {@code headerLength},
 *       {@code footerLength}, {@code tagLength}, {@code lengthSize}, {@code lengthStart} and
 *       {@code lengthEnd}. The script returns {@code true} once a whole message is in
 *       {@code buff}. It may write an operation name to {@code out} and store the request text
 *       with {@code context.setProperty(name, request)}, as before - both are optional now.</li>
 * </ul>
 *
 * <p>Only the bytes of the message are removed from the buffer. Anything after it - the start
 * of the next message - stays for the next round.
 */
public final class TcpMessageFramer {

    /** One complete message. */
    public static final class Frame {
        private final String operation;
        private final String content;

        Frame(String operation, String content) {
            this.operation = operation;
            this.content = content;
        }

        /** Operation named by a handler script, or null. */
        public String getOperation() {
            return operation;
        }

        /** The whole message (header + body + footer) as ISO-8859-1 text. */
        public String getContent() {
            return content;
        }
    }

    private final TcpFrameSpec spec;
    private final String script;

    /**
     * @param script handler script to use, or empty/null for the built-in framer
     */
    public TcpMessageFramer(TcpFrameSpec spec, String script) {
        this.spec = spec;
        this.script = script == null ? "" : script.trim();
    }

    public boolean usesScript() {
        return !script.isEmpty();
    }

    /**
     * The next complete message, removed from {@code pipe}; null when more bytes are needed.
     *
     * @throws IllegalArgumentException when the header's length field is not a number - the
     *         connection's data cannot be framed and should be dropped
     */
    public Frame next(IoBufferPipeAdapter pipe, Context context) {
        return usesScript() ? nextByScript(pipe, context) : nextBuiltIn(pipe);
    }

    private Frame nextBuiltIn(IoBufferPipeAdapter pipe) {
        byte[] data = pipe.peek();
        int length = spec.completeLength(data, data.length);
        if (length < 0) {
            return null;
        }
        String content = new String(data, 0, length, TcpFrameSpec.CHARSET);
        pipe.consume(length);
        return new Frame(null, content);
    }

    private Frame nextByScript(IoBufferPipeAdapter pipe, Context context) {
        pipe.position(0);
        Out out = new Out();
        HashMap<String, Object> props = new HashMap<>(spec.asScriptVariables());
        props.put("buff", pipe);
        props.put("out", out);
        props.put("context", context);

        String result = new GroovyScriptExecutor().executeScript(script, props, context);
        if (!"true".equalsIgnoreCase(result == null ? "" : result.trim())) {
            pipe.position(0);
            return null;
        }

        String operation = out.getRequest();
        operation = operation == null || operation.isBlank() ? null : operation.trim();

        // Bytes the script read through buff.get(...) are the message. A script that only
        // peeked (position still 0) is taken to have consumed everything received so far.
        int consumed = pipe.position() > 0 ? Math.min(pipe.position(), pipe.size()) : pipe.size();
        byte[] data = pipe.peek();
        String content = new String(data, 0, consumed, TcpFrameSpec.CHARSET);

        // ReadyAPI-style scripts also stored the request in the context under the operation name.
        if (operation != null) {
            Object stored = context.getDynamicProperties().get(operation);
            if (stored != null && !stored.toString().isEmpty()) {
                content = stored.toString();
            }
        }

        pipe.consume(consumed);
        Logger.getInstance().info("[TCP] handler script framed " + consumed + " bytes"
                + (operation == null ? "" : " for operation " + operation));
        return new Frame(operation, content);
    }
}
