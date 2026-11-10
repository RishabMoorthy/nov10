package org.framework.properties;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.session.IoSession;
import org.framework.utils.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;

public class Out {

    private final StringBuilder buffer = new StringBuilder();

    // Mimics out.write(Object)
    public void write(Object arg0) throws IOException {
        try {
            if (arg0 instanceof IoBuffer) {
                byte[] bytes = new byte[((IoBuffer) arg0).remaining()];
                ((IoBuffer) arg0).get(bytes);
                System.out.println("req " + new String(bytes));
                buffer.append(new String(bytes));
            } else if (arg0 instanceof byte[]) {
                buffer.append(new String((byte[]) arg0, java.nio.charset.StandardCharsets.ISO_8859_1));
            } else if (arg0 != null) {
                // a plain operation name, e.g. out.write("SenderReq")
                buffer.append(arg0.toString());
            }
        } catch (Exception e) {
            Logger.getInstance().info(e);
        }
    }

    public String getRequest() {
        return buffer.toString();
    }

    public void clear() {
        buffer.setLength(0);
    }
}

