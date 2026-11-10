package org.framework.core.impl;

import com.stubio.util.TCPOperation;

import org.framework.core.BaseRoute;
import org.framework.core.RequestHandler;
import org.framework.core.ResponseResolver;
import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.services.tcp.TcpFrameSpec;
import org.framework.utils.Logger;

import java.util.Collections;
import java.util.List;

/**
 * TCP dispatch over the com.stubio model (TCPService / StubOperation / RRPair).
 *
 * <p>A TCP message carries no path or method, so the operation is chosen like this:
 * <ol>
 *   <li>the name a request handler script wrote to {@code out} (ReadyAPI style), if it matches;</li>
 *   <li>a {@code context.operation} property set by a script, if it matches;</li>
 *   <li>the only operation, when the service has exactly one (TIBCO: SenderReq).</li>
 * </ol>
 * With several operations and no name, the request gets "No operation matched".
 *
 * <p>After that, the operation's RRPair chain runs exactly as for REST/SOAP (Script /
 * Selective / Operation, defaultRR held back), and {@link ResponseResolver} applies the
 * execution mode - Stand In, Live Invocation or Failover.
 */
public class TcpRequestHandler implements RequestHandler {

    private final List<TCPOperation> operations;
    private final ResponseResolver resolver;

    /** Old routes (pre-.vs ReadyAPI format). Kept only so that path still compiles and works. */
    private final List<BaseRoute> legacyRoutes;

    public TcpRequestHandler(List<TCPOperation> operations, ResponseResolver resolver) {
        this.operations = operations == null ? Collections.emptyList() : operations;
        this.resolver = resolver;
        this.legacyRoutes = null;
    }

    /** Legacy constructor for the old route-based TCP model. */
    public TcpRequestHandler(List<BaseRoute> routes) {
        this.operations = Collections.emptyList();
        this.resolver = null;
        this.legacyRoutes = routes;
    }

    @Override
    public MockResponse handleRequest(Context context, MockRequest request) throws Exception {

        if (legacyRoutes != null) {
            for (BaseRoute route : legacyRoutes) {
                if (route.matchRequest(context, request)) {
                    return route.getResponse(context, request);
                }
            }
            return new MockResponse("404 Not Found".getBytes());
        }

        TCPOperation operation = selectOperation(context, request);
        if (operation == null) {
            Logger.getInstance().warn("[TCP] no operation matched '" + request.getOperation() + "' among "
                    + operations.size() + " operations");
            return new MockResponse("No Operation Matched", null, 404,
                    "No operation matched this request".getBytes(TcpFrameSpec.CHARSET), "", "", "");
        }

        request.setOperation(operation.getName());
        Logger.getInstance().info("[TCP] operation : " + operation.getName());
        return resolver.getResponse(operation, context, request);
    }

    private TCPOperation selectOperation(Context context, MockRequest request) {
        TCPOperation byName = byName(request.getOperation());
        if (byName != null) {
            return byName;
        }
        Object fromContext = context.getDynamicProperties().get("operation");
        if (fromContext != null) {
            byName = byName(fromContext.toString());
            if (byName != null) {
                return byName;
            }
        }
        return operations.size() == 1 ? operations.get(0) : null;
    }

    private TCPOperation byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        for (TCPOperation op : operations) {
            if (wanted.equals(op.getName())) {
                return op;
            }
        }
        for (TCPOperation op : operations) {
            if (wanted.equalsIgnoreCase(op.getName())) {
                return op;
            }
        }
        return null;
    }
}
