package org.framework.services.tcp;

import com.stubio.util.TCPHandler;
import com.stubio.util.TCPRequestHandler;
import com.stubio.util.TCPResponseHandler;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How one TCP message is framed, taken from the six TCP Handler fields of the .vs:
 *
 * <pre>
 *   HeaderLength   bytes before the body                       TIBCO: 24
 *   FooterLength   bytes after the body                        TIBCO: 9   (|$|END|$|)
 *   TagLength      TLV tag size inside the body                TIBCO: 3
 *   LengthSize     TLV length size inside the body             TIBCO: 2
 *   Start / End    where the body length sits in the header,   TIBCO: 15 / 18
 *                  0-based, End INCLUSIVE (the tool's convention)
 * </pre>
 *
 * <p>The body length read from the header counts the body only - header and footer are
 * added on top. A complete message is therefore {@code header + bodyLength + footer} bytes.
 *
 * <p>When HeaderLength or Start/End are not filled in, the service has no fixed framing and
 * every chunk received is treated as one complete message.
 *
 * <p>TCP payloads are handled as ISO-8859-1 throughout, so every byte maps to exactly one
 * char and back. Positions in the header, {@code String.substring} in scripts and the length
 * field all mean the same thing, and the bytes sent back are exactly the bytes configured.
 */
public final class TcpFrameSpec {

    public static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    private final int headerLength;
    private final int footerLength;
    private final int tagLength;
    private final int lengthSize;
    private final int lengthStart;
    private final int lengthEnd;      // inclusive
    private final String requestScript;
    private final String responseScript;

    private TcpFrameSpec(int headerLength, int footerLength, int tagLength, int lengthSize,
            int lengthStart, int lengthEnd, String requestScript, String responseScript) {
        this.headerLength = headerLength;
        this.footerLength = footerLength;
        this.tagLength = tagLength;
        this.lengthSize = lengthSize;
        this.lengthStart = lengthStart;
        this.lengthEnd = lengthEnd;
        this.requestScript = requestScript;
        this.responseScript = responseScript;
    }

    /** Reads the TCP Handlers block. A missing or blank field becomes -1 ("not set"). */
    public static TcpFrameSpec from(TCPHandler handler) {
        TCPRequestHandler req = handler == null ? null : handler.getRequestHandler();
        TCPResponseHandler res = handler == null ? null : handler.getResponseHandler();

        int header = req == null ? -1 : toInt(req.getHeaderLength(), "HeaderLength");
        int footer = req == null ? -1 : toInt(req.getFooterLength(), "FooterLength");
        int tag = req == null ? -1 : toInt(req.getTagLength(), "TagLength");
        int size = req == null ? -1 : toInt(req.getLengthSize(), "LengthSize");
        int start = req == null ? -1 : toInt(req.getContentStartPosition(), "ContentLengthPosition/Start");
        int end = req == null ? -1 : toInt(req.getContentEndPosition(), "ContentLengthPosition/End");

        String reqScript = req == null || req.getScript() == null ? "" : trim(req.getScript().getScript());
        String resScript = res == null || res.getScript() == null ? "" : trim(res.getScript().getScript());

        TcpFrameSpec spec = new TcpFrameSpec(header, Math.max(footer, 0), tag, size, start, end, reqScript, resScript);
        spec.validate();
        return spec;
    }

    private void validate() {
        if (!isFramed()) {
            return;
        }
        if (lengthStart > lengthEnd) {
            throw new IllegalArgumentException("TCP Handler: ContentLengthPosition Start (" + lengthStart
                    + ") is after End (" + lengthEnd + ")");
        }
        if (lengthEnd >= headerLength) {
            throw new IllegalArgumentException("TCP Handler: ContentLengthPosition End (" + lengthEnd
                    + ") must be inside the header (HeaderLength " + headerLength + ")");
        }
    }

    /** True when HeaderLength and the length position are all set. */
    public boolean isFramed() {
        return headerLength > 0 && lengthStart >= 0 && lengthEnd >= 0;
    }

    /**
     * The size of the first complete message in {@code data}, or -1 when more bytes are
     * needed. Without framing every non-empty chunk is one complete message.
     *
     * @throws IllegalArgumentException when the length field in the header is not a number
     */
    public int completeLength(byte[] data, int available) {
        if (available <= 0) {
            return -1;
        }
        if (!isFramed()) {
            return available;
        }
        if (available < headerLength) {
            return -1;
        }
        int total = headerLength + bodyLength(data) + footerLength;
        return available >= total ? total : -1;
    }

    /** The body length written in the header at Start..End. */
    public int bodyLength(byte[] header) {
        String field = new String(header, lengthStart, lengthEnd - lengthStart + 1, CHARSET).trim();
        try {
            int value = Integer.parseInt(field);
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("TCP header length field (positions " + lengthStart + "-"
                    + lengthEnd + ") is '" + field + "', not a number");
        }
    }

    /** The body of a complete message: everything between header and footer. */
    public String bodyOf(String message) {
        if (!isFramed() || message == null) {
            return message;
        }
        int from = Math.min(headerLength, message.length());
        int to = Math.max(from, message.length() - footerLength);
        return message.substring(from, to);
    }

    /**
     * Splits a TLV body into tag -> value. Only the first occurrence of a repeated tag
     * is kept. Returns an empty map when TagLength / LengthSize are not set or the body
     * is not valid TLV - this is a convenience for scripts and templates, never an error.
     */
    public Map<String, String> parseTlv(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || tagLength <= 0 || lengthSize <= 0) {
            return values;
        }
        int p = 0;
        try {
            while (p + tagLength + lengthSize <= body.length()) {
                String tag = body.substring(p, p + tagLength);
                p += tagLength;
                int len = Integer.parseInt(body.substring(p, p + lengthSize).trim());
                p += lengthSize;
                if (len < 0 || p + len > body.length()) {
                    break;
                }
                values.putIfAbsent(tag, body.substring(p, p + len));
                p += len;
            }
        } catch (NumberFormatException e) {
            // not TLV past this point - keep what was read
        }
        return values;
    }

    /**
     * Writes {@code bodyLength} into the header's length field, zero-padded to the field
     * width. Returns the header unchanged when it does not fit the configured framing.
     */
    public String withBodyLength(String header, int bodyLength) {
        if (!isFramed() || header == null || header.length() <= lengthEnd) {
            return header;
        }
        int width = lengthEnd - lengthStart + 1;
        String digits = String.valueOf(bodyLength);
        if (digits.length() > width) {
            return header;
        }
        String padded = "0".repeat(width - digits.length()) + digits;
        return header.substring(0, lengthStart) + padded + header.substring(lengthEnd + 1);
    }

    /** The header's length field as a number, or -1 when it is not numeric. */
    public int lengthFieldOf(String header) {
        if (!isFramed() || header == null || header.length() <= lengthEnd) {
            return -1;
        }
        try {
            return Integer.parseInt(header.substring(lengthStart, lengthEnd + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- accessors ----

    public int getHeaderLength() { return headerLength; }
    public int getFooterLength() { return footerLength; }
    public int getTagLength() { return tagLength; }
    public int getLengthSize() { return lengthSize; }
    public int getLengthStart() { return lengthStart; }
    public int getLengthEnd() { return lengthEnd; }
    public String getRequestScript() { return requestScript; }
    public String getResponseScript() { return responseScript; }
    public boolean hasRequestScript() { return !requestScript.isEmpty(); }
    public boolean hasResponseScript() { return !responseScript.isEmpty(); }

    /** The six fields as script variables, so a handler script never has to hard-code them. */
    public Map<String, Object> asScriptVariables() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("headerLength", headerLength);
        vars.put("footerLength", footerLength);
        vars.put("tagLength", tagLength);
        vars.put("lengthSize", lengthSize);
        vars.put("lengthStart", lengthStart);
        vars.put("lengthEnd", lengthEnd);
        return vars;
    }

    @Override
    public String toString() {
        return "header=" + headerLength + " footer=" + footerLength + " tag=" + tagLength
                + " lengthSize=" + lengthSize + " lengthPos=" + lengthStart + "-" + lengthEnd
                + (hasRequestScript() ? " requestScript=yes" : "")
                + (hasResponseScript() ? " responseScript=yes" : "");
    }

    private static int toInt(String raw, String field) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("TCP Handler: " + field + " is '" + value + "', not a number");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
