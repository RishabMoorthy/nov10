package org.framework.core;

import com.stubio.util.Endpoint;
import com.stubio.util.StubOperation;
import com.stubio.util.TCPOperation;

import org.framework.config.ServiceConfig;
import org.framework.core.impl.MockRequestHandler;
import org.framework.core.impl.TcpRequestHandler;
import org.framework.datasource.DataSourceService;
import org.framework.services.soap.SoapRequestHandler;

import java.io.IOException;
import java.util.List;

public class RequestHandlerFactory {

    /**
     * REST and SOAP dispatch over the com.stubio model (Endpoint /
     * StubOperation), sharing a ResponseResolver that carries the response
     * selection logic BaseRoute used to own.
     */
    public static RequestHandler getInstance(ServiceConfig config) throws IOException {
        RequestHandler handler = null;

        if ("Rest".equals(config.getType())) {
            List<Endpoint> endpoints = config.getEndpoints();
            ResponseResolver resolver = new ResponseResolver(config.getServiceName());
            resolver.setProperties(config.getGenericProperties());
            resolver.setDataSourceService(dataSourceServiceOf(config));
            endpoints.forEach(resolver::register);
            handler = new MockRequestHandler(endpoints, resolver);

        } else if ("Soap".equals(config.getType())) {
            List<StubOperation> operations = config.getStubOperations();
            ResponseResolver resolver = new ResponseResolver(config.getServiceName());
            resolver.setProperties(config.getGenericProperties());
            resolver.setDataSourceService(dataSourceServiceOf(config));
            operations.forEach(resolver::register);
            handler = new SoapRequestHandler(operations, resolver);

        } else if ("Tcp".equals(config.getType())) {
            if (config.getRoutes() != null && !config.getRoutes().isEmpty()) {
                // old route-based TCP model
                handler = new TcpRequestHandler(config.getRoutes());
            } else {
                List<TCPOperation> operations = config.getTcpOperations();
                ResponseResolver resolver = new ResponseResolver(config.getServiceName());
                resolver.setProperties(config.getGenericProperties());
                resolver.setDataSourceService(dataSourceServiceOf(config));
                resolver.setTcpFrameSpec(config.getTcpFrameSpec());
                operations.forEach(resolver::register);
                handler = new TcpRequestHandler(operations, resolver);
            }
        }

        return handler;
    }

    /** Null when the service declares no data sources. */
    private static DataSourceService dataSourceServiceOf(ServiceConfig config) {
        return config.isDatasourceEnabled() ? config.getDataSourceService() : null;
    }
}

