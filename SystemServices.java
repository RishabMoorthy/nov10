package org.framework.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.stubio.util.ExecutionMode;
import com.stubio.util.VirtualServiceObject;
import io.jsonwebtoken.Claims;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Part;
import org.codehaus.jackson.map.ObjectMapper;
import org.common.db.config.ConfigLoader;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.framework.config.LogConfigManager;
import org.framework.config.ServiceConfig;
import org.framework.core.*;
import org.framework.db.Utility;
import org.framework.db.service.ExecutionModeSyncException;
import org.framework.utils.*;
import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.framework.Main.upandRunning;
import static org.framework.constants.PathConstants.VS_XML_BACKUP_DIRECTORY;
import static org.framework.constants.PathConstants.VS_XML_DIRECTORY;
import static org.framework.constants.PathConstants.VS_FILE_EXTENSION;

public class SystemServices {
    private Server jettyServer;
    private final JwtValidator validator;
    private ExecutorService executor; // optional if you need extra async tasks (Jetty has its own pool)

    public SystemServices() throws IOException {
        // Initialize validator (same secret & skew)
        String secret = "abf93-2kd8fj-9sd3k2-qp93jf-xk92lm";

        this.validator = new JwtValidator(secret,
                /* allowedClockSkewSeconds= */
                60);

        // Build Jetty (HTTP only here; add HTTPS if you want)
        int port = Integer.parseInt(ConfigLoader.getProperty("stubserver.port"));
        int maxThreads = parseIntOrDefault(ConfigLoader.getProperty("jetty.maxThreads"), 200);
        int minThreads = parseIntOrDefault(ConfigLoader.getProperty("jetty.minThreads"), 8);
        int idleTimeoutMs = parseIntOrDefault(ConfigLoader.getProperty("jetty.idleTimeoutMs"), 30000);

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeoutMs);
        this.jettyServer = new Server(threadPool);

        HttpConfiguration httpConfig = new HttpConfiguration();
        ServerConnector httpConnector = new ServerConnector(jettyServer, new HttpConnectionFactory(httpConfig));
        String host = CustomMethods.getLocalHostAddress();
        if (host != null && !host.isEmpty())
            httpConnector.setHost("0.0.0.0");
        httpConnector.setPort(port);
        jettyServer.setConnectors(new ServerConnector[] { httpConnector });
    }

    public void start() throws Exception {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }

        // One handler that routes by path (preserves your existing functionality)
        jettyServer.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target,
                    Request baseRequest,
                    HttpServletRequest request,
                    HttpServletResponse response) throws IOException, ServletException {

                // Always add CORS headers
                addCors(response);
                // Preflight handling
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    baseRequest.setHandled(true);
                    return;
                }

                // Default Content-Type for JSON responses
                response.setContentType("application/json; charset=UTF-8");

                try {
                    // Route by exact path
                    String path = request.getRequestURI();
                    switch (path) {
                        case "/sbackend/stop":
                            handleStop(request, response);
                            break;
                        case "/sbackend/delete":
                            handleDelete(request, response);
                            break;
                        case "/sbackend/getServiceEndpoints":
                            handleGetServiceEndpoints(request, response);
                            break;
                        case "/sbackend/getServiceLogs":
                            handleGetServiceLogs(request, response);
                            break;
                        case "/sbackend/getServicesList":
                            handleGetServicesList(request, response);
                            break;
                        case "/sbackend/ReqResLogConfig":
                            handleReqResLogConfig(request, response);
                            break;
                        case "/sbackend/RespTimeConfig":
                            handleRespTimeConfig(request, response);
                            break;
                        case "/sbackend/getReqResConfig":
                            handleGetReqResConfig(request, response);
                            break;
                        case "/sbackend/healthCheck":
                            handleHealthCheck(request, response);
                            break;
                        case "/sbackend/uploadFile":
                            handleUploadFile(request, response);
                            break;
                        case "/sbackend/getServiceXml":
                            handleGetServiceXml(request, response);
                            break;
                        case "/sbackend/setDelay":
                            handleSetDelay(request, response);
                            break;
                        case "/sbackend/getDelayConfig":
                            handleGetDelayConfig(request, response);
                            break;
                        case "/sbackend/start":
                            handleStart(request, response);
                            break;
                        case "/sbackend/revert":
                            handleRevert(request, response);
                            break;
                        case "/sbackend/deploy":
                            handleDeploy(request, response);
                            break;
                        case "/sbackend/refreshExecutionMode":
                            handleRefreshExecutionMode(request, response);
                            break;
                        default:
                            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                            writeJson(response, error("Not Found", "Unknown endpoint: " + path));
                            break;
                    }
                } catch (ExecutionModeSyncException e) {
                    // stubserver.ip unset, or the service missing from the catalog -
                    // the operator needs the reason, not a stack trace.
                    Logger.getInstance().error("ExecutionMode configuration error", e);
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    writeJson(response, error("Execution Mode Not Configured", e.getMessage()));
                } catch (Exception e) {
                    Logger.getInstance().error("SystemServices handler error", e);
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(response, error("Internal Server Error", e.getMessage()));
                } finally {
                    baseRequest.setHandled(true);
                }
            }

            // ---------- Endpoint handlers ----------

            private void handleStop(HttpServletRequest req, HttpServletResponse resp) throws IOException {

                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser")))
                    return;
                StartService request = parseJson(req, StartService.class);
                String serviceName = request != null ? request.getServiceName() : null;
                JSONObject response = new JSONObject();

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }
                try {
                    boolean isStopped = ServerManager.getInstance().stopService(serviceName);
                    response.put("message", isStopped ? "Service Stopped" : "Service doesn't exists :");
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, response);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "DELETE")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;
                StartService request = parseJson(req, StartService.class);
                String serviceName = request != null ? request.getServiceName() : null;
                JSONObject response = new JSONObject();

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    boolean isDeleted = ServerManager.getInstance().deleteService(serviceName);
                    response.put("message", isDeleted ? "Service deleted successfully." : "Service doesn't exist.");
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, response);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleGetServiceEndpoints(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser", "Guest")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                // String serviceName = request != null && request.has("serviceName") ?
                // request.get("serviceName").getAsString() : null;

                String queryString = req.getQueryString();
                String serviceName = "";
                if (queryString != null && !queryString.isEmpty()) {
                    String[] parts = queryString.split("=");
                    serviceName = parts.length > 1 ? parts[1] : null;
                    Logger.getInstance().info("service name for get config: " + serviceName);
                }
                if (serviceName == null || serviceName.isEmpty()) {
                    JSONObject response = new JSONObject();
                    resp.setStatus(400);
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    Object vsResponse = getVSData(serviceName);
                    resp.setStatus(HttpServletResponse.SC_OK);
                    // vsResponse may be JSONObject or POJO – write as JSON string
                    writeJsonString(resp, vsResponse.toString());
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleGetServiceLogs(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser", "Guest")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                // String serviceName = request != null && request.has("serviceName") ?
                // request.get("serviceName").getAsString() : null;

                String queryString = req.getQueryString();
                String serviceName = "";
                if (queryString != null && !queryString.isEmpty()) {
                    String[] parts = queryString.split("=");
                    serviceName = parts.length > 1 ? parts[1] : null;
                    Logger.getInstance().info("service name for get config: in logs" + serviceName);
                }

                if (serviceName == null || serviceName.isEmpty()) {
                    JSONObject response = new JSONObject();
                    resp.setStatus(400);
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }
                try {
                    EndpointsStats response = RequestTracker.getAllLogs(serviceName);
                    String status = (ServerManager.getInstance().getServices().get(serviceName).isRunning()) ? "Running"
                            : "Stopped";
                    String json = "{}";
                    if (response != null) {
                        response.setStatus(status);
                        ObjectMapper mapper = new ObjectMapper();
                        json = mapper.writeValueAsString(response);
                    } else {
                        Object vsResponse = getVSData(serviceName);
                        if (vsResponse instanceof JSONObject) {
                            json = ((JSONObject) vsResponse).toString();
                        } else if (vsResponse != null) {
                            ObjectMapper mapper = new ObjectMapper();
                            json = mapper.writeValueAsString(vsResponse);
                        }
                    }
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJsonString(resp, json);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleGetServicesList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser", "Guest")))
                    return;
                try {
                    JSONArray serviceList = Utility.getInstance().getServicesFromDb();
                    if (serviceList != null) {
                        for (int i = 0; i < serviceList.length(); i++) {

                            JSONObject response = serviceList.getJSONObject(i);
                            EndpointsStats list = RequestTracker.getAllLogs((String) response.get("serviceName"));
                            long count = 0;
                            if (list != null && list.getCount() != 0) {
                                count = list.getCount();
                            }
                            response.remove("keepReqResLogsDays");
                            response.remove("keepReqResLogs");
                            response.put("count", count);
                            response.put("upAndRunning", ServerManager.getInstance().getServices()
                                    .get((String) response.get("serviceName")).getTimestamp());
                            boolean canRevert = new File(
                                    VS_XML_BACKUP_DIRECTORY + "/" + response.get("serviceName") + VS_FILE_EXTENSION).exists();
                            response.put("canRevert", canRevert);
                        }

                    }
                    JSONObject respObj = new JSONObject();
                    respObj.put("upAndRunning", upandRunning);
                    respObj.put("servicesList", serviceList);

                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, respObj);
                } catch (Exception e) {
                    Logger.getInstance().error("get service list api: ", e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleReqResLogConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;

                try {
                    ObjectMapper mapper = new ObjectMapper();
                    ReqResConfig request = mapper.readValue(req.getInputStream(), ReqResConfig.class);

                    if (request.getServiceName() == null || request.getServiceName().isEmpty()
                            || request.isSaveLog().isEmpty()) {
                        resp.setStatus(400);
                        JSONObject response = new JSONObject();
                        response.put("message", "Invalid request body");
                        writeJson(resp, response);
                        return;
                    }

                    LogConfigManager.updateServiceLog(request.getServiceName(), request.isSaveLog(),
                            request.getKeepDataFor());
                    Utility.getInstance().updateServiceLogInDB(request.getServiceName(), request.isSaveLog(),
                            request.getKeepDataFor());
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJsonString(resp, "Success");
                } catch (Exception e) {
                    Logger.getInstance().error("/sbackend/ReqResLogConfig", e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleRespTimeConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;

                try {
                    ObjectMapper mapper = new ObjectMapper();
                    RespTimeConfig request = mapper.readValue(req.getInputStream(), RespTimeConfig.class);
                    if (request.getServiceName() == null || request.getServiceName().isEmpty()
                            || request.getSaveRespTime().isEmpty()) {
                        resp.setStatus(400);
                        JSONObject response = new JSONObject();
                        response.put("message", "Invalid request body");
                        writeJson(resp, response);
                        return;
                    }

                    RespTimeConfigManager.updateRespTimeConfig(request.getServiceName(), request.getSaveRespTime());
                    Utility.getInstance().updateServiceRespTimeConfigInDB(request.getServiceName(),
                            request.getSaveRespTime());
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJsonString(resp, "Success");
                } catch (Exception e) {
                    Logger.getInstance().error("/sbackend/RespTimeConfig", e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleGetReqResConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser")))
                    return;

                try {
                    Map<String, String> configMap = LogConfigManager.getConfig();
                    Map<String, String> respTimeConfig = RespTimeConfigManager.getConfig();

                    String queryString = req.getQueryString();
                    List<Map<String, Object>> jsonList = new ArrayList<>();

                    if (queryString != null && !queryString.isEmpty()) {
                        String[] parts = queryString.split("=");
                        String serviceName = parts.length > 1 ? parts[1] : null;
                        Logger.getInstance().info("service name for get config: " + serviceName);
                        Map<String, Object> obj = new HashMap<>();
                        obj.put("service", serviceName);
                        obj.put("enabled", configMap.get(serviceName));
                        obj.put("saveRespTime", respTimeConfig.get(serviceName));
                        jsonList.add(obj);
                    } else {
                        for (Map.Entry<String, String> entry : configMap.entrySet()) {
                            Map<String, Object> obj = new HashMap<>();
                            obj.put("service", entry.getKey());
                            obj.put("enabled", entry.getValue());
                            jsonList.add(obj);
                        }
                    }

                    Map<String, Object> finalResponse = new HashMap<>();
                    finalResponse.put("keepDataFor", LogConfigManager.getDays());
                    finalResponse.put("services", jsonList);

                    ObjectMapper mapper = new ObjectMapper();
                    String jsonResponse = mapper.writeValueAsString(finalResponse);

                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJsonString(resp, jsonResponse);
                } catch (Exception e) {
                    Logger.getInstance().error("GetReqResConfig", e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            /**
             * Re-reads the execution mode from the database for one deployed service
             * and applies it, in memory and in the .vs file.
             *
             * <p>Called by the portal backend right after it updates
             * VS_EXECUTIONMODE or moves the active VS_LIVEURLS flag, so a mode change
             * takes effect on the next request instead of on the next restart. Safe
             * to call repeatedly: when the database and the service already agree it
             * does nothing.
             *
             * <p>The virtual server is this server's own stubserver.ip, so no server
             * identity is taken from the request.
             */
            private void handleRefreshExecutionMode(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {

                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    writeJson(resp, error("Method Not Allowed", "Use POST."));
                    return;
                }

                String serviceName = req.getParameter("service");
                if (serviceName == null || serviceName.trim().isEmpty()) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    writeJson(resp, error("Bad Request", "Query parameter 'service' is required."));
                    return;
                }
                serviceName = serviceName.trim();

                AbstractService service = ServerManager.getInstance().getService(serviceName);
                if (service == null) {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    writeJson(resp, error("Not Found",
                            "Service '" + serviceName + "' is not deployed on this server."));
                    return;
                }

                ServiceConfig config = service.getConfig();

                // An ExecutionModeSyncException here is a configuration problem; the
                // dispatcher turns it into a 400 carrying the reason.
                Utility.getInstance().syncExecutionMode(config);

                ExecutionMode mode = config.getExecutionModeModel();
                String resolvedMode = mode == null || mode.getExeModeValue() == null
                        ? ""
                        : mode.getExeModeValue().trim();

                JSONObject body = new JSONObject();
                body.put("message", "Execution mode refreshed");
                body.put("serviceName", serviceName);
                body.put("executionMode", resolvedMode);
                body.put("liveEndpoint", config.getRouteEndpoint());

                Logger.getInstance().info("[ExecutionMode] refresh requested for " + serviceName
                        + " -> mode=" + resolvedMode);

                resp.setStatus(HttpServletResponse.SC_OK);
                writeJson(resp, body);
            }

            private void handleHealthCheck(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                resp.setStatus(HttpServletResponse.SC_OK);
                writeJsonString(resp, "Success");
            }

            private void handleGetServiceXml(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                // String serviceName = request != null ?
                // request.get("serviceName").getAsString() : null;

                String queryString = req.getQueryString();
                String serviceName = "";
                if (queryString != null && !queryString.isEmpty()) {
                    String[] parts = queryString.split("=");
                    serviceName = parts.length > 1 ? parts[1] : null;
                    Logger.getInstance().info("service name for get config: " + serviceName);
                }

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    JSONObject response = new JSONObject();
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    AbstractService service = ServerManager.getInstance().getServices().get(serviceName);
                    JSONObject serviceList = new JSONObject();
                    serviceList.put("serviceName", service.getName());
                    serviceList.put("status", service.isRunning() ? "Running" : "Stopped");
                    serviceList.put("port", service.getConfig().getPort());
                    serviceList.put("xmlContent", service.getConfig().getXmlFileContent());

                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, serviceList);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleUploadFile(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {

                if (!"POST".equalsIgnoreCase(req.getMethod())) {
                    resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    return;
                }
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;

                // Enable multipart
                req.setAttribute("org.eclipse.jetty.multipartConfig",
                        new MultipartConfigElement("/tmp"));

                String serviceName = req.getParameter("serviceName");

                if (serviceName == null || serviceName.trim().isEmpty()) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    resp.getWriter().write("serviceName is required");
                    return;
                }

                Part filePart = req.getPart("file");

                if (filePart == null || filePart.getSize() == 0) {
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    resp.getWriter().write("file is required");
                    return;
                }

                // Extract safe filename (avoid path traversal)
                String originalFileName = Paths.get(filePart.getSubmittedFileName())
                        .getFileName()
                        .toString();

                // Base directory
                String baseDir = "vsfiles/dataset";
                File serviceDir = new File(baseDir, serviceName);

                // Create directory if not exists
                if (!serviceDir.exists()) {
                    serviceDir.mkdirs();
                }

                // Final file path (will overwrite if exists)
                File outputFile = new File(serviceDir, originalFileName);

                try (InputStream input = filePart.getInputStream();
                        FileOutputStream output = new FileOutputStream(outputFile, false)) { // false = overwrite

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = input.read(buffer)) != -1) {
                        output.write(buffer, 0, len);
                    }
                }

                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write("File uploaded successfully");
            }

            private void handleSetDelay(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                String serviceName = null;

                try {
                    if (request == null) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        writeJson(resp, error("Bad Request", "Request body is empty or not valid JSON"));
                        return;
                    }
                    if (!request.has("serviceName") || request.get("serviceName").isJsonNull()) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        writeJson(resp, error("Bad Request", "Missing required field: serviceName"));
                        return;
                    }
                    serviceName = request.get("serviceName").getAsString().trim();
                    if (serviceName.isEmpty()) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        writeJson(resp, error("Bad Request", "serviceName cannot be empty"));
                        return;
                    }
                    if (!request.has("delayMode")) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        writeJson(resp, error("Bad Request", "Missing 'delayMode'"));
                        return;
                    }

                    AbstractService service = ServerManager.getInstance().getServices().get(serviceName);
                    ObjectMapper mapper = new ObjectMapper();
                    ServiceConfig config = service.getConfig();
                    mapper.readerForUpdating(config).readValue(request.toString());
                    // Persist
                    Utility.getInstance().updateCustomDelayConfigInDB(
                            config.getServiceName(),
                            config.getDelayMs(),
                            config.getDelayMode(),
                            config.getUpperMs(),
                            config.getLowerMs(),
                            config.getMedianMs(),
                            config.getStandardDeviation(),
                            config.getTotalTxn(),
                            config.getDelayPercent());

                    JsonObject response = new JsonObject();
                    response.addProperty("message", "Delay configured successfully");
                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJsonString(resp, response.toString());
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleGetDelayConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "GET")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                // String serviceName = request != null ?
                // request.get("serviceName").getAsString().trim() : null;

                String queryString = req.getQueryString();
                String serviceName = "";
                if (queryString != null && !queryString.isEmpty()) {
                    String[] parts = queryString.split("=");
                    serviceName = parts.length > 1 ? parts[1] : null;
                    Logger.getInstance().info("service name for get config: " + serviceName);
                }

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    JSONObject response = new JSONObject();
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    AbstractService service = ServerManager.getInstance().getServices().get(serviceName);
                    JSONObject obj = new JSONObject();
                    ServiceConfig config = service.getConfig();

                    if ("FIXED".equalsIgnoreCase(config.getDelayMode())) {
                        obj.put("delayMs", config.getDelayMs());
                    } else if ("RANDOM".equalsIgnoreCase(config.getDelayMode())) {
                        obj.put("lowerMs", config.getLowerMs());
                        obj.put("upperMs", config.getUpperMs());
                    } else if ("REALISTIC".equalsIgnoreCase(config.getDelayMode())) {
                        obj.put("delayMs", config.getDelayMs());
                        obj.put("totalTxn", config.getTotalTxn());
                        obj.put("delayPercent", config.getDelayPercent());
                    } else if ("LOGNORMAL".equalsIgnoreCase(config.getDelayMode())) {
                        obj.put("medianMs", config.getMedianMs());
                        obj.put("standardDeviation", config.getStandardDeviation());

                    }
                    obj.put("delayMode", config.getDelayMode());

                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, obj);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin", "ApplicationUser")))
                    return;
                StartService request = parseJson(req, StartService.class);
                String serviceName = request != null ? request.getServiceName() : null;
                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    JSONObject response = new JSONObject();
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    if ("POST".equalsIgnoreCase(req.getMethod())) {
                        boolean isStarted = ServerManager.getInstance().startService(serviceName);
                        JSONObject response = new JSONObject();
                        response.put("message", isStarted ? "Service Started" : "Service doesn't exist :");

                        Optional.ofNullable(RequestTracker.getAllLogs(serviceName))
                                .ifPresent(logs -> logs.setCount(0));

                        resp.setStatus(HttpServletResponse.SC_OK);
                        writeJson(resp, response);
                    } else if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    } else {
                        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    }
                } catch (ExecutionModeSyncException e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    writeJson(resp, error("Service Not Started", e.getMessage()));
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private void handleRevert(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;
                JsonObject request = parseJson(req, JsonObject.class);
                String serviceName = request != null ? request.get("serviceName").getAsString() : null;

                if (serviceName == null || serviceName.isEmpty()) {
                    resp.setStatus(400);
                    JSONObject response = new JSONObject();
                    response.put("message", "Invalid request body");
                    writeJson(resp, response);
                    return;
                }

                try {
                    String message = CustomMethods.getInstance().revertXml(serviceName);
                    JSONObject obj = new JSONObject();
                    obj.put("message", message);

                    String xmlContent = Files.readString(
                            Path.of(VS_XML_DIRECTORY, serviceName + VS_FILE_EXTENSION),
                            StandardCharsets.UTF_8);
                    ServerManager.getInstance().getServices().get(serviceName).getConfig()
                            .setXmlFileContent(xmlContent);

                    resp.setStatus(HttpServletResponse.SC_OK);
                    writeJson(resp, obj);
                } catch (Exception e) {
                    Logger.getInstance().error(serviceName, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            @SuppressWarnings("resource")
            private void handleDeploy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (!validateMethod(req.getMethod(), "POST")) {
                    resp.setStatus(405);
                    return;
                }
                ;
                if (!validateToken(req, resp, Arrays.asList("Admin")))
                    return;

                JSONObject response = new JSONObject();
                VirtualServiceObject parsedObj = null;
                String user = "";
                String isRedeploy = "false";
                String forceDeploy = "";
                File extractedFile = null;
                try {
                    String contentType = req.getHeader("Content-Type");
                    String queryString = req.getQueryString();
                    if (queryString != null && !queryString.isEmpty()) {
                        for (String param : queryString.split("&")) {
                            if (param.startsWith("isRedeploy=")) {
                                isRedeploy = param.split("=")[1];
                            } else if (param.startsWith("user")) {
                                user = param.split("=")[1];
                            } else if (param.startsWith("forceDeploy")) {
                                forceDeploy = param.split("=")[1];
                            }
                        }
                    }

                    if (contentType != null && contentType.contains("multipart/form-data")) {
                        String boundary = null;
                        String[] partsCT = contentType.split("boundary=");
                        if (partsCT.length > 1) {
                            boundary = partsCT[1];
                        }

                        // File extractedFile = extractFile(req.getInputStream(), boundary);

                        MultiPartData data = new MultiPartData();
                        MultiPartData mp = data.extractMultiPart(req.getInputStream(), boundary);

                        extractedFile = mp.file;

                        // Only .vs virtual service files can be deployed.
                        if (extractedFile == null) {
                            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                            writeJson(resp, error("Bad Request", "file is required"));
                            return;
                        }
                        if (mp.fileName == null || !mp.fileName.toLowerCase().endsWith(VS_FILE_EXTENSION)) {
                            Files.deleteIfExists(extractedFile.toPath());
                            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                            writeJson(resp, error("Invalid File Type",
                                    "Only " + VS_FILE_EXTENSION + " files can be deployed. Received: " + mp.fileName));
                            return;
                        }
                        String backendApplication = mp.backendApplication;
                        String group = mp.group;
                        String backendType = mp.backendType;
                        String storetoMasterCatalog = mp.storetoMasterCatalog;
                        String envType = mp.envType;

                        System.out.println("PARTNER = " + backendApplication);

                        // parseXml now throws on a bad file; the outer catch reports it.
                        parsedObj = ServerManager.getInstance().parseXml(extractedFile);
                        System.out.println("service name : " + parsedObj.getVsName());
                        if (Utility.getInstance().isExistingService(parsedObj.getVsName(), parsedObj.getPort())) {
                            storetoMasterCatalog = "true";
                        }

                        {
                            String fileName = extractedFile != null ? parsedObj.getVsName() : null;
                            if (extractedFile != null) {
                                AbstractService currentService = ServerManager.getInstance().getService(fileName);

                                if (currentService != null && currentService.isRunning()) {
                                    if ("true".equals(isRedeploy)) {
                                        if (currentService.isRunning()) {
                                            storetoMasterCatalog = "true";
                                            ServerManager.getInstance().stopService(fileName);
                                            CustomMethods.getInstance().backupXmlFile(currentService.getName());
                                        }
                                    }
                                }
                                if (currentService != null && "false".equals(isRedeploy)) {
                                    response.put("message", "Service is already Running");
                                    response.put("port", parsedObj.getPort());
                                    response.put("serviceName", parsedObj.getVsName());
                                    resp.setStatus(HttpServletResponse.SC_OK);
                                    writeJson(resp, response);
                                    return;
                                }

                                if (currentService == null) {
                                    if (forceDeploy.equals("true")) {
                                        Map<String, AbstractService> services = ServerManager.getInstance()
                                                .getServices();
                                        for (Map.Entry<String, AbstractService> entry : services.entrySet()) {
                                            String name = entry.getKey();
                                            AbstractService service = entry.getValue();

                                            System.out.println("Service Name: " + name);
                                            if (service.getConfig().getPort() == parsedObj.getPort()) {
                                                ServerManager.getInstance().stopService(name);
                                                // ServerManager.getInstance().startService(parsedObj.getVsName());
                                            }

                                        }
                                    }
                                    // call method
                                    AbstractService service = findServiceByPort(parsedObj.getPort());
                                    if (service != null) {
                                        response.put("message", "Service is already Running on same port");
                                        response.put("port", parsedObj.getPort());
                                        response.put("serviceName", parsedObj.getVsName());
                                        resp.setStatus(HttpServletResponse.SC_OK);
                                        writeJson(resp, response);
                                        return;
                                    }
                                }

                                    if (ServerManager.getInstance().deployService(parsedObj, true, user, backendApplication,
                                            group, backendType, Boolean.parseBoolean(storetoMasterCatalog), envType)) {
                                        RequestTracker.removeLogs(parsedObj.getVsName());
                                        response.put("message", "Service Deployed Successfully");
                                        response.put("port", parsedObj.getPort());
                                        response.put("serviceName", parsedObj.getVsName());
                                        UploadFile(extractedFile, fileName);
                                        resp.setStatus(HttpServletResponse.SC_OK);
                                        Logger.getInstance().info("Service Deployed Successfully " + parsedObj.getVsName());
                                    } else {
                                        response.put("message", "Service Not Deployed");
                                        resp.setStatus(HttpServletResponse.SC_OK);
                                        Logger.getInstance().info("Service Not Successfully " + parsedObj.getVsName());
                                    }
                                } else {
                                    response.put("message", "File Extract Failed");
                                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                }
                            }
                            writeJson(resp, response);
                        } else {
                            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                            writeJson(resp, error("Unsupported Media Type", "Use multipart/form-data"));
                        }
                } catch (ExecutionModeSyncException e) {
                    // A setup problem the operator has to fix - give them the reason,
                    // not "Something went wrong".
                    String svc = parsedObj != null ? parsedObj.getVsName() : "unknown";
                    Logger.getInstance().error(svc, e);
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    writeJson(resp, error("Service Not Deployed", e.getMessage()));
                } catch (Exception e) {
                    String svc = parsedObj != null ? parsedObj.getVsName() : "unknown";
                    Logger.getInstance().error(svc, e);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    writeJson(resp, error("Error", "Something went wrong"));
                }
            }

            private AbstractService findServiceByPort(int port) {
                Map<String, AbstractService> services = ServerManager.getInstance().getServices();
                for (AbstractService s : services.values()) {
                    if (s != null && s.getConfig() != null && s.getConfig().getPort() == port && s.isRunning()) {
                        return s;
                    }
                }
                return null;
            }

            // ---------- Helpers ----------

            private void addCors(HttpServletResponse resp) {
                resp.setHeader("Access-Control-Allow-Origin", "*");
                resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                resp.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
                resp.setHeader("Access-Control-Max-Age", "86400");
            }

            private <T> T parseJson(HttpServletRequest req, Class<T> type) throws IOException {
                try (InputStream is = req.getInputStream();
                        InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                        BufferedReader br = new BufferedReader(isr)) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null)
                        sb.append(line);
                    String body = sb.toString();
                    if (body == null || body.isEmpty())
                        return null;
                    if (type == JsonObject.class) {
                        return type.cast(new Gson().fromJson(body, JsonObject.class));
                    }
                    return new ObjectMapper().readValue(body, type);
                }
            }

            private void writeJson(HttpServletResponse resp, JSONObject obj) throws IOException {
                byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
                resp.setContentLength(bytes.length);
                try (OutputStream os = resp.getOutputStream()) {
                    os.write(bytes);
                }
            }

            private void writeJsonString(HttpServletResponse resp, String json) throws IOException {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                resp.setContentLength(bytes.length);
                try (OutputStream os = resp.getOutputStream()) {
                    os.write(bytes);
                }
            }

            private JSONObject error(String title, String detail) {
                JSONObject obj = new JSONObject();
                obj.put("error", title);
                obj.put("detail", detail);
                return obj;
            }

            private Claims getClaims(String authHeader) {
                Claims claims = null;
                try {
                    claims = validator.validateBearer(authHeader);
                    return claims;
                } catch (Exception e) {
                    return claims;
                }
            }

            private boolean validateToken(HttpServletRequest req, HttpServletResponse resp,
                    List<String> sufficientRoles) throws IOException {
                String authHeader = req.getHeader("Authorization");
                JSONObject response = new JSONObject();

                Claims claims = getClaims(authHeader);
                if (claims == null) {
                    response.put("message", "Invalid or Expired token");
                    resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    writeJson(resp, response);
                    return false;
                }

                Set<String> roles = Authz.getRoles(claims);
                if (!Authz.hasAnyRole(roles, sufficientRoles.toArray(new String[0]))) {
                    response.put("message", "Insufficient role");
                    resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    writeJson(resp, response);
                    return false;
                }
                return true;
            }

            private boolean validateMethod(String reqMethod, String expectedMethod) {

                return expectedMethod.equalsIgnoreCase(reqMethod);
            }

            private Object getVSData(String vsName) {
                JSONObject resp = new JSONObject();
                try {
                    String port = "";
                    String type = "";
                    List<String> operationName = new ArrayList<>();
                    List<String> endpoints = new ArrayList<>();
                    String status = "";

                    AbstractService service = ServerManager.getInstance().getServices().get(vsName);
                    port = String.valueOf(service.getConfig().getPort());
                    status = service.isRunning() ? "Running" : "Stopped";
                    type = service.getConfig().getType();
                    String httpSecure = service.getConfig().getHttpSecure();

                    String protocol;
                    if (httpSecure == null) {
                        protocol = "";
                    } else if ("true".equalsIgnoreCase(httpSecure.trim())) {
                        protocol = "https://";
                    } else if ("false".equalsIgnoreCase(httpSecure.trim())) {
                        protocol = "http://";
                    } else {
                        protocol = "";
                    }

                    if ("Rest".equals(type)) {
                        for (com.stubio.util.Endpoint endpoint : service.getConfig().getEndpoints()) {
                            System.out.println("path is " + endpoint.getPath());
                            endpoints.add(protocol + CustomMethods.getLocalHostAddress() + ":" + port
                                    + endpoint.getPath());
                        }
                    } else if ("Soap".equals(type)) {
                        for (com.stubio.util.StubOperation operation : service.getConfig().getStubOperations()) {
                            endpoints.add(protocol + CustomMethods.getLocalHostAddress() + ":" + port);
                            operationName.add(operation.getName());
                        }
                    } else if ("Tcp".equals(type) && service.getConfig().getRoutes() == null) {
                        for (com.stubio.util.TCPOperation operation : service.getConfig().getTcpOperations()) {
                            endpoints.add("tcp://" + CustomMethods.getLocalHostAddress() + ":" + port);
                            operationName.add(operation.getName());
                        }
                    } else if (service.getConfig().getRoutes() != null) {
                        // legacy TCP
                        for (org.framework.core.BaseRoute route : service.getConfig().getRoutes()) {
                            endpoints.add(protocol + CustomMethods.getLocalHostAddress() + ":" + port);
                            operationName.add(route.getName());
                        }
                    }

                    resp.put("serviceName", vsName);
                    resp.put("port", port);
                    resp.put("status", status);
                    resp.put("type", type);
                    resp.put("endpoints", endpoints);
                    resp.put("operationName", operationName);
                } catch (Exception e) {
                    Logger.getInstance().error(vsName, e);
                    e.printStackTrace();
                }
                return resp;
            }

            private File extractFile(InputStream inputStream, String boundary) throws IOException {
                // Keeps your simplistic scanner approach for multipart (same behavior)
                try (Scanner scanner = new Scanner(inputStream, StandardCharsets.ISO_8859_1.name())
                        .useDelimiter("--" + boundary)) {
                    while (scanner.hasNext()) {
                        String part = scanner.next();

                        if (part.contains("Content-Disposition: form-data;") && part.contains("filename=")) {
                            String uniqueFileName = "temp_" + UUID.randomUUID() + VS_FILE_EXTENSION;
                            if (uniqueFileName != null && !uniqueFileName.isEmpty()) {
                                int dataStartIndex = part.indexOf("\r\n\r\n") + 4;
                                String filedata = part.substring(dataStartIndex).trim();
                                File fileDir = new File("tempfiles");
                                if (!fileDir.exists()) {
                                    fileDir.mkdirs();
                                }
                                Path tempTargetPath = Paths.get("tempfiles", uniqueFileName);
                                Files.writeString(tempTargetPath, filedata, StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING);
                                return tempTargetPath.toFile();
                            }
                        }

                        int headerEndIdx = part.indexOf("\r\n\r\n");
                        if (headerEndIdx < 0) {
                            continue;
                        }

                        String headers = part.substring(0, headerEndIdx);
                        String content = part.substring(headerEndIdx + 4);

                        String h = headers.toLowerCase();

                        boolean isFormData = h.contains("content-disposition:") && h.contains("form-data");
                        boolean isPartnerName = h.contains("name=\"partner\"") || h.contains("name=partner");
                        boolean isFile = h.contains("filename=");

                        if (isFormData && isPartnerName && !isFile) {
                            if (content.endsWith("\r\n")) {
                                content = content.substring(0, content.length() - 2);
                            }
                            String partnerValue = content.trim();
                            System.out.println("partner = [" + partnerValue + "]");
                        }
                    }
                } catch (Exception e) {
                    Logger.getInstance().error("Error in extract file", e);
                    e.printStackTrace();
                }
                return null;
            }

            private void UploadFile(File extractedFile, String fileName) throws IOException {
                try {
                    fileName = fileName + VS_FILE_EXTENSION;
                    File fileDir = new File("vsfiles");
                    if (!fileDir.exists()) {
                        fileDir.mkdirs();
                    }
                    File file = new File(fileDir, fileName);
                    Files.move(extractedFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    Logger.getInstance().error("Error in upload file " + fileName, e);
                    e.printStackTrace();
                }
            }

            // util
            private int parseIntOrDefault(String s, int def) {
                try {
                    return (s == null || s.isEmpty()) ? def : Integer.parseInt(s.trim());
                } catch (Exception e) {
                    return def;
                }
            }
        });

        jettyServer.start();
        Logger.getInstance()
                .info("Jetty SystemServices started on port " + ConfigLoader.getProperty("stubserver.port"));
    }

    public void stop() {
        if (jettyServer != null) {
            try {
                jettyServer.stop();
                jettyServer.destroy();
            } catch (Exception e) {
                Logger.getInstance().error("Error stopping SystemServices Jetty", e);
            }
        }
    }

    // util (class level)
    private static int parseIntOrDefault(String s, int def) {
        try {
            return (s == null || s.isEmpty()) ? def : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}

