package org.framework.config;

import com.stubio.util.Endpoint;
import com.stubio.util.ExecutionMode;
import com.stubio.util.KeyStore;
import com.stubio.util.SecuritySettings;
import com.stubio.util.StubOperation;
import com.stubio.util.TCPOperation;
import com.stubio.util.VirtualServiceObject;

import org.framework.core.BaseRoute;
import org.framework.core.EventHandler;
import org.framework.core.RequestHandler;
import org.framework.datasource.DataSourceService;
import org.framework.properties.Properties;
import org.framework.services.tcp.TcpFrameSpec;

import com.stubio.util.GenericProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Deployed-service configuration.
 *
 * <p>
 * The parsed configuration is now the {@link VirtualServiceObject} produced by
 * com.stubio's VirtualServiceMapper - this class holds it directly rather than
 * copying its fields into a parallel model. Everything below is either a
 * delegating accessor onto that object, or genuinely runtime state that has no
 * place in the parsed model (request handler, data source service, live xml
 * content, transaction counters).
 *
 * <p>
 * The four script fields are the one exception: RestService/SoapService rewrite
 * script text at start-up (the com.vs.met.* class remapping), so they are held
 * locally and seeded from the virtual service's CustomScripts.
 */
public class ServiceConfig {

    public static final String SCRIPT_VS_START = "VS Start";
    public static final String SCRIPT_VS_STOP = "VS Stop";
    public static final String SCRIPT_ON_REQUEST = "On Request";
    public static final String SCRIPT_ON_RESPONSE = "On Response";

    /** The parsed configuration, straight from the new parser. */
    private VirtualServiceObject virtualService;

    // ---- runtime-only state ----
    private String userName;
    private Properties properties;
    private String xmlFileContent;

    private String onRequestScript;
    private String startScript;
    private String stopScript;
    private String afterRequestScript;

    private int totalTxn;
    private int delayPercent;

    private RequestHandler requestHandler;
    private EventHandler eventHandler;

    private DataSourceService dataSourceService;

    private int delayMs = 0;
    private String delayMode = "FIXED";
    private int lowerMs = 0;
    private int upperMs = 0;
    private double medianMs = 0.0;
    private double standardDeviation = 0.0;

    /** Legacy TCP routes. REST/SOAP dispatch off the virtual service model. */
    private List<BaseRoute> routes;

    // ---- the parsed model ----

    public VirtualServiceObject getVirtualService() {
        return virtualService;
    }

    /**
     * Installs the parsed virtual service and seeds the mutable script fields
     * from its CustomScripts block.
     */
    public void setVirtualService(VirtualServiceObject virtualService) {
        this.virtualService = virtualService;
        this.startScript = scriptText(SCRIPT_VS_START);
        this.stopScript = scriptText(SCRIPT_VS_STOP);
        this.onRequestScript = scriptText(SCRIPT_ON_REQUEST);
        this.afterRequestScript = scriptText(SCRIPT_ON_RESPONSE);
    }

    private String scriptText(String executionType) {
        if (virtualService == null || virtualService.getCustomScripts() == null) {
            return null;
        }
        com.stubio.util.Script script = virtualService.getCustomScripts().get(executionType);
        if (script == null || script.getScript() == null || script.getScript().isBlank()) {
            return null;
        }
        return script.getScript().trim();
    }

    // ---- delegating accessors onto the parsed model ----

    public String getServiceName() {
        return virtualService == null ? null : virtualService.getVsName();
    }

    public int getPort() {
        return virtualService == null ? 0 : virtualService.getPort();
    }

    public String getHost() {
        return virtualService == null ? null : virtualService.getHost();
    }

    public String getContextPath() {
        return virtualService == null ? null : virtualService.getContextPath();
    }

    /** "Rest" / "Soap" / "Tcp" - AbstractServiceFactory and the DB layer expect this casing. */
    public String getType() {
        if (virtualService == null || virtualService.getVsType() == null) {
            return null;
        }
        if ("soap".equalsIgnoreCase(virtualService.getVsType())) {
            return "Soap";
        }
        if ("tcp".equalsIgnoreCase(virtualService.getVsType())) {
            return "Tcp";
        }
        return "Rest";
    }

    public String getAutoStart() {
        return virtualService != null && virtualService.isAutoStart() ? "true" : "false";
    }

    public String getHttpSecure() {
        return virtualService != null && virtualService.isSecured() ? "true" : "false";
    }

    public String getProtocol() {
        return virtualService != null && virtualService.isSecured() ? "HTTPS" : "HTTP";
    }

    /** Service-level delay - was con:minApplicationDelay, now Config/ResponseDelay. */
    public int getDelay() {
        return virtualService == null ? 0 : virtualService.getResponseDelay();
    }

    public String getWsdlContent() {
        if (virtualService == null || virtualService.getWsdlMetaData() == null) {
            return null;
        }
        return virtualService.getWsdlMetaData().getWsdlText();
    }

    public List<Endpoint> getEndpoints() {
        if (virtualService == null || virtualService.getRestService() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(virtualService.getRestService().getEndpoints());
    }

    public List<StubOperation> getStubOperations() {
        if (virtualService == null || virtualService.getSoapService() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(virtualService.getSoapService().getStubOperations());
    }

    public List<TCPOperation> getTcpOperations() {
        if (virtualService == null || virtualService.getTcpService() == null
                || virtualService.getTcpService().getTcpOperations() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(virtualService.getTcpService().getTcpOperations());
    }

    /**
     * The six TCP Handler fields plus the handler scripts. Built once and cached; a bad value
     * (e.g. HeaderLength "abc") fails here, at deploy, rather than on the first request.
     */
    public synchronized TcpFrameSpec getTcpFrameSpec() {
        if (tcpFrameSpec == null) {
            tcpFrameSpec = TcpFrameSpec.from(virtualService == null ? null : virtualService.getTcpHandler());
        }
        return tcpFrameSpec;
    }

    private TcpFrameSpec tcpFrameSpec;

    public boolean isDatasourceEnabled() {
        return dataSourceService != null && dataSourceService.getRegistry() != null;
    }

    // ---- live invocation, from ExecutionMode ----

    /** The configured mode, or {@link ExecutionModeType#NONE} when unset or unrecognised. */
    public ExecutionModeType getExecutionModeType() {
        ExecutionMode mode = executionMode();
        return ExecutionModeType.from(mode == null ? null : mode.getExeModeValue());
    }

    /**
     * True for Live Invocation and Failover - the two modes that call the destination
     * server. Failover differs only in what happens when that call fails.
     */
    public boolean isLiveModeEnabled() {
        return getExecutionModeType().callsLiveServer();
    }

    /** True when a failed live call must be answered with the mock response. */
    public boolean isFailoverMode() {
        return getExecutionModeType().fallsBackToMock();
    }

    /**
     * @deprecated retained for callers predating Failover support. It now answers true for
     *             Failover as well, since that mode also calls the live server; use
     *             {@link #isLiveModeEnabled()} or {@link #getExecutionModeType()} instead.
     */
    @Deprecated
    public boolean isRouteModeEnabled() {
        return isLiveModeEnabled();
    }

    /** Base URL of the active LiveURL - was the routeEndpoint attribute. */
    public String getRouteEndpoint() {
        ExecutionMode mode = executionMode();
        if (mode == null || mode.getLiveURLs() == null || mode.getLiveURLs().isEmpty()) {
            return "";
        }

        ExecutionMode.LiveURL selected = null;
        for (ExecutionMode.LiveURL liveURL : mode.getLiveURLs()) {
            if (liveURL.isActive()) {
                selected = liveURL;
                break;
            }
        }
        if (selected == null) {
            selected = mode.getLiveURLs().get(0);
        }

        String transport = trimToEmpty(selected.getTransportType());
        StringBuilder endpoint = new StringBuilder(transport.isEmpty() ? "http" : transport);
        endpoint.append("://").append(trimToEmpty(selected.getHost()));

        String port = trimToEmpty(selected.getPort());
        if (!port.isEmpty()) {
            endpoint.append(":").append(port);
        }

        // BasePath is parsed and kept in the .vs, but takes no part in the live
        // endpoint - the live host is transport://host:port only.
        return endpoint.toString();
    }

    /**
     * The ExecutionMode this service actually runs on.
     *
     * <p>Exposed so ExecutionDetailsService can push database values onto the same
     * object the request path reads, which is what lets a portal mode change take
     * effect without a restart.
     */
    public ExecutionMode getExecutionModeModel() {
        return executionMode();
    }

    /** ExecutionMode sits at root for REST and inside SOAPService for SOAP. */
    private ExecutionMode executionMode() {
        if (virtualService == null) {
            return null;
        }
        ExecutionMode mode = virtualService.getExeMode();
        boolean rootEmpty = mode == null || trimToEmpty(mode.getExeModeValue()).isEmpty();
        if (rootEmpty && virtualService.getSoapService() != null) {
            return virtualService.getSoapService().getExeMode();
        }
        return mode;
    }

    // ---- keystore, from SecuritySettings ----

    public String getKeystorePath() {
        KeyStore keyStore = keyStore();
        return keyStore == null ? null : keyStore.getPath();
    }

    public String getKeystorePassword() {
        KeyStore keyStore = keyStore();
        return keyStore == null ? null : keyStore.getKeyPass();
    }

    public String getKeystoreType() {
        KeyStore keyStore = keyStore();
        return keyStore == null ? null : keyStore.getType();
    }

    public boolean isClientAuth() {
        SecuritySettings settings = securitySettings();
        return settings != null && settings.isClientAuth();
    }

    private KeyStore keyStore() {
        SecuritySettings settings = securitySettings();
        if (settings == null || settings.getKeyStoreMap() == null) {
            return null;
        }
        return settings.getKeyStoreMap().get("KeyStore");
    }

    private SecuritySettings securitySettings() {
        if (virtualService == null) {
            return null;
        }
        if ("soap".equalsIgnoreCase(virtualService.getVsType())) {
            return virtualService.getSoapService() == null
                    ? null
                    : virtualService.getSoapService().getSecuritySettings();
        }
        return virtualService.getRestService() == null
                ? null
                : virtualService.getRestService().getSecuritySettings();
    }

    // ---- runtime state ----

    public DataSourceService getDataSourceService() {
        return dataSourceService;
    }

    public void setDataSourceService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getXmlFileContent() {
        return xmlFileContent;
    }

    public void setXmlFileContent(String xmlFileContent) {
        this.xmlFileContent = xmlFileContent;
    }

    public String getOnRequestScript() {
        return onRequestScript;
    }

    public void setOnRequestScript(String onRequestScript) {
        this.onRequestScript = onRequestScript;
    }

    public String getStartScript() {
        return startScript;
    }

    public void setStartScript(String startScript) {
        this.startScript = startScript;
    }

    public String getStopScript() {
        return stopScript;
    }

    public void setStopScript(String stopScript) {
        this.stopScript = stopScript;
    }

    public String getAfterRequestScript() {
        return afterRequestScript;
    }

    public void setAfterRequestScript(String afterRequestScript) {
        this.afterRequestScript = afterRequestScript;
    }

    public List<BaseRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(List<BaseRoute> routes) {
        this.routes = routes;
    }

    public EventHandler getEventHandler() {
        return eventHandler;
    }

    public void setEventHandler(EventHandler handler) {
        this.eventHandler = handler;
    }

    public RequestHandler getRequestHandler() {
        return requestHandler;
    }

    public void setRequestHandler(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public int getTotalTxn() {
        return totalTxn;
    }

    public void setTotalTxn(int totalTxn) {
        this.totalTxn = totalTxn;
    }

    public int getDelayPercent() {
        return delayPercent;
    }

    public void setDelayPercent(int delayPercent) {
        this.delayPercent = delayPercent;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public String getDelayMode() {
        return delayMode;
    }

    public void setDelayMode(String delayMode) {
        this.delayMode = delayMode;
    }

    public int getLowerMs() {
        return lowerMs;
    }

    public void setLowerMs(int lowerMs) {
        this.lowerMs = lowerMs;
    }

    public int getUpperMs() {
        return upperMs;
    }

    public void setUpperMs(int upperMs) {
        this.upperMs = upperMs;
    }

    public double getMedianMs() {
        return medianMs;
    }

    public void setMedianMs(double medianMs) {
        this.medianMs = medianMs;
    }

    public double getStandardDeviation() {
        return standardDeviation;
    }

    public void setStandardDeviation(double standardDeviation) {
        this.standardDeviation = standardDeviation;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** VS root-level &lt;vs:Properties&gt; as parsed by StubEngineUtil. */
    public List<GenericProperty> getGenericProperties() {
        if (virtualService == null || virtualService.getPropertyList() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(virtualService.getPropertyList());
    }
}

