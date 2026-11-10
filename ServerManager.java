package org.framework.core;

import com.stubio.util.DataBase;
import com.stubio.util.DataFile;
import com.stubio.util.DataSourceSelect;
import com.stubio.util.Endpoint;
import com.stubio.util.GenericProperty;
import com.stubio.util.StubOperation;
import com.stubio.util.TCPOperation;
import com.stubio.util.VirtualServiceMapper;
import com.stubio.util.VirtualServiceObject;

import org.framework.config.LogConfigManager;
import org.framework.config.ServiceConfig;
import org.framework.constants.PathConstants;
import org.framework.datasource.AccessMode;
import org.framework.datasource.ConnectionPoolRegistry;
import org.framework.datasource.DataSourceDefinition;
import org.framework.datasource.DataSourceException;
import org.framework.datasource.DataSourceFactory;
import org.framework.datasource.DataSourceRegistry;
import org.framework.datasource.DataSourceService;
import org.framework.datasource.DataSourceType;
import org.framework.datasource.ExcelDataSource;
import org.framework.datasource.SqlTemplate;
import org.framework.db.Utility;
import org.framework.db.service.ExecutionModeSyncException;
import org.framework.properties.Properties;
import org.framework.utils.CustomMethods;
import org.framework.utils.FolderDeleteService;
import org.framework.utils.Logger;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ServerManager {

    private static ServerManager instance = new ServerManager();

    public Map<String, AbstractService> getServices() {
        return services;
    }

    private Map<String, AbstractService> services = new HashMap<>();

    private ServerManager(){
    }

    public static ServerManager getInstance() {
        return instance;
    }

    public AbstractService getService(String serviceName) {
        return services.get(serviceName);
    }

    /**
     * Parses a virtual service file with the com.stubio parser.
     *
     * <p>
     * The SoapUI class remapping below is retained for Groovy script bodies that
     * were carried over from the old format; it is inert for files that never
     * referenced com.eviware.
     */
    public VirtualServiceObject parseXml(File file) throws Exception {
        String content = new String (Files.readAllBytes(file.toPath()));
        //System.out.println(content);
        Files.write(file.toPath(),content.getBytes() );

        if (!content.contains("stubVirtualService")) {
            throw new UnsupportedOperationException(
                    "Not a stubVirtualService file. TCP (customMockService) and the old SoapUI formats "
                            + "are not supported by this parser - re-export the service in the new format.");
        }

        // DocumentBuilder.parse(String) resolves its argument as a URI, so a plain
        // path containing a space would fail here.
        return new VirtualServiceMapper(file.toURI().toString()).getVso();
    }

    public boolean deployService(VirtualServiceObject vso, boolean isDeployedFromUI, String user, String backendApplication, String group, String backendType, boolean storeToMasterCatalog, String envType) throws Exception {
        ServiceConfig config = new ServiceConfig();
        boolean isStoredInDb = false;
        try{
            config.setVirtualService(vso);
            config.setUserName(user);
            config.setXmlFileContent(readXmlContent(vso));
            config.setProperties(mapProperties(vso));
            config.setDataSourceService(buildDataSourceService(vso));

            AbstractService restService = AbstractServiceFactory.getInstance(config);
            config.setRequestHandler(RequestHandlerFactory.getInstance(config));
            restService.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));

            services.put(restService.getName(), restService);
            RespTimeConfigManager.getConfig().put(restService.getName(), "No");
            LogConfigManager.getConfig().put(restService.getName(), "No");

            //delete if any data source present when data source flag is false
            if(!config.isDatasourceEnabled()){
                FolderDeleteService.deleteServiceFolder(config.getServiceName());
            }

            boolean started = false;
            try{
                if (config.getAutoStart().equalsIgnoreCase("true")) {
                    restService.start();
                    started = true;
                }
                else{
                    restService.start();
                    restService.stop();
                    started = true;
                }
                boolean isStoredInMasterCatalog = false;
                if(storeToMasterCatalog)
                    isStoredInMasterCatalog = Utility.getInstance().storeServiceInMasterCatalog(restService.getName(), restService, backendApplication, group, backendType, envType);
                isStoredInDb =  Utility.getInstance().storeServiceInDataBase(restService.getName(), restService, isDeployedFromUI);
                if(!(isStoredInDb)){
                    try{
                        restService.stop();
                        services.remove(restService.getName());
                    }
                    catch(Exception e){
                        Logger.getInstance().error(config.getServiceName(), e);
                        e.printStackTrace();
                        return false;
                    }
                }
                return true;

            } catch(ExecutionModeSyncException e){
                // A misconfiguration the operator has to fix. Roll the service back
                // out and let the message reach the deploy response verbatim.
                Logger.getInstance().error(config.getServiceName(), e);
                if(started)
                    restService.stop();
                services.remove(restService.getName());
                throw e;
            } catch(Exception e){
                e.printStackTrace();
                Logger.getInstance().error(config.getServiceName(), e);
                if(started)
                    restService.stop();
                return false;
            }

            //store into db
        }
        catch(ExecutionModeSyncException e){
            throw e;
        }
        catch(Exception e){
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            Logger.getInstance().error("Error occurred in "+ config.getServiceName() + "\n" + sw.toString());
            return false;
        }
    }

    /** VirtualServiceObject.getXmlPath() holds the file URI we handed the mapper. */
    private String readXmlContent(VirtualServiceObject vso) throws Exception {
        String xmlPath = vso.getXmlPath();
        if (xmlPath == null || xmlPath.isBlank()) {
            return Files.readString(Paths.get(PathConstants.VS_XML_DIRECTORY, vso.getVsName() + PathConstants.VS_FILE_EXTENSION));
        }
        if (xmlPath.startsWith("file:")) {
            return Files.readString(Paths.get(java.net.URI.create(xmlPath)));
        }
        return Files.readString(Paths.get(xmlPath));
    }

    /** vs:Properties/vs:Variable pairs. */
    private Properties mapProperties(VirtualServiceObject vso) {
        Properties properties = new Properties();
        if (vso.getPropertyList() != null) {
            for (GenericProperty property : vso.getPropertyList()) {
                if (property.getKey() != null && !property.getKey().isBlank()) {
                    properties.addProperty(property.getKey(),
                            property.getValue() == null ? "" : property.getValue().trim());
                }
            }
        }
        return properties;
    }

    /**
     * Builds the service's data sources from every endpoint / operation
     * DataSourceSelect, with the connections from {@code <vs:DataSources>}.
     *
     * <p>
     * The registry is left null when nothing usable is declared - that is what
     * {@link ServiceConfig#isDatasourceEnabled()} checks. Problems are logged
     * against the endpoint and the data source is skipped, so one bad entry does
     * not stop the service from deploying.
     */
    private DataSourceService buildDataSourceService(VirtualServiceObject vso) {
        DataSourceRegistry registry = new DataSourceRegistry();
        ConnectionPoolRegistry connectionPools = new ConnectionPoolRegistry();
        Map<String, String> owners = new HashMap<>();
        boolean registered = false;

        if (vso.getRestService() != null) {
            for (Endpoint endpoint : vso.getRestService().getEndpoints()) {
                String label = endpoint.getMethod() + " " + endpoint.getPath();
                registered |= registerDataSources(endpoint.getDataSourceSelect(), label, vso,
                        registry, connectionPools, owners);
            }
        }
        if (vso.getSoapService() != null) {
            for (StubOperation operation : vso.getSoapService().getStubOperations()) {
                registered |= registerDataSources(operation.getDataSourceSelect(), operation.getName(), vso,
                        registry, connectionPools, owners);
            }
        }
        if ("tcp".equalsIgnoreCase(vso.getVsType()) && vso.getTcpService() != null
                && vso.getTcpService().getTcpOperations() != null) {
            for (TCPOperation operation : vso.getTcpService().getTcpOperations()) {
                registered |= registerDataSources(operation.getDataSourceSelect(), operation.getName(), vso,
                        registry, connectionPools, owners);
            }
        }

        if (!registered) {
            Logger.getInstance().info("DataSource is not enabled for service " + vso.getVsName());
            return new DataSourceService(null, new DataSourceFactory(), connectionPools);
        }
        return new DataSourceService(registry, new DataSourceFactory(), connectionPools);
    }

    private boolean registerDataSources(DataSourceSelect select, String label, VirtualServiceObject vso,
            DataSourceRegistry registry, ConnectionPoolRegistry connectionPools, Map<String, String> owners) {
        if (select == null) {
            return false;
        }
        String where = "[DataSource] " + vso.getVsName() + " " + label;
        boolean registered = false;

        for (DataBase database : nullSafe(select.getDatabase())) {
            String name = trim(database.getDsName());
            String connectionName = trim(database.getConnectionName());
            String query = database.getQuery() == null ? "" : database.getQuery().trim();
            if (name.isEmpty() && connectionName.isEmpty() && query.isEmpty()) {
                continue; // the empty <vs:Database/> the writer always emits
            }
            if (name.isEmpty()) {
                Logger.getInstance().error(where + ": a Database data source has no DataSourceName - skipped");
                continue;
            }
            if (!isUniqueName(name, label, where, owners)) {
                continue;
            }
            com.stubio.util.DataSource connection = vso.getDataSourceList() == null
                    ? null
                    : vso.getDataSourceList().get(connectionName);
            if (connection == null) {
                Logger.getInstance().error(where + " " + name + ": ConnectionName '" + connectionName
                        + "' is not in <vs:DataSources> - skipped");
                continue;
            }
            if (query.isEmpty()) {
                Logger.getInstance().error(where + " " + name + ": Query is empty - skipped");
                continue;
            }
            try {
                SqlTemplate.compile(query);
                connectionPools.register(connectionName, connection.getDriver(), jdbcUrl(connection),
                        connection.getUser(), connection.getPwd(), connectionProperties(connection));
            } catch (Exception e) {
                Logger.getInstance().error(where + " " + name + ": " + e.getMessage() + " - skipped");
                continue;
            }

            registry.register(new DataSourceDefinition(name, DataSourceType.SQL, AccessMode.QUERY)
                    .addProperty(DataSourceService.PROP_CONNECTION_NAME, connectionName)
                    .addProperty(DataSourceService.PROP_QUERY, query));
            owners.put(name, label);
            registered = true;
            Logger.getInstance().info("Registered Database DataSource: " + name + " on connection "
                    + connectionName + " (" + connectionPools.getUrl(connectionName) + ") for " + label);
        }

        for (DataFile file : nullSafe(select.getFile())) {
            String name = trim(file.getDsName());
            String fileLocation = trim(file.getFileLocation());
            String fetchType = trim(file.getFetchType());
            if (name.isEmpty() && fileLocation.isEmpty() && fetchType.isEmpty()) {
                continue; // the empty <vs:File/> the writer always emits
            }
            if (name.isEmpty() || fileLocation.isEmpty()) {
                Logger.getInstance().error(where + ": a File data source needs both DataSourceName and"
                        + " FileLocation - skipped");
                continue;
            }
            if (!isUniqueName(name, label, where, owners)) {
                continue;
            }

            // FileLocation is where the file was picked from on the uploader's machine;
            // the server reads its own uploaded copy from the dataset folder.
            String fileName = Paths.get(fileLocation.replace('\\', '/')).getFileName().toString();
            String datasetPath = PathConstants.DATASET_BASE_PATH + vso.getVsName() + File.separator + fileName;
            AccessMode accessMode = mapFetchType(fetchType, where + " " + name);
            int randomCount = file.getRandomCount() == null ? 0 : Math.max(0, file.getRandomCount());

            // A File data source is always an Excel workbook.
            registry.register(new DataSourceDefinition(name, DataSourceType.EXCEL, accessMode)
                    .addProperty("filePath", datasetPath)
                    .addProperty(ExcelDataSource.PROP_WORKSHEET, file.getSheet())
                    .addProperty(ExcelDataSource.PROP_HEADER_ROW_INDEX, 0)
                    .addProperty(ExcelDataSource.PROP_WINDOW_SIZE, randomCount));
            owners.put(name, label);
            registered = true;
            Logger.getInstance().info("Registered File DataSource: " + name + " (" + datasetPath + ", sheet "
                    + file.getSheet() + ", FetchType " + accessMode + ") for " + label);
        }

        return registered;
    }

    /** DataSourceName must be unique in the service, because values are stored as Name.COLUMN. */
    private boolean isUniqueName(String name, String label, String where, Map<String, String> owners) {
        if (owners.containsKey(name)) {
            Logger.getInstance().error(where + " " + name + ": DataSourceName is already used by "
                    + owners.get(name) + " - skipped. Each data source needs its own name.");
            return false;
        }
        return true;
    }

    /** FetchType: Sequential, Random, or Query (also the default when empty). */
    private AccessMode mapFetchType(String fetchType, String where) {
        switch (fetchType.toUpperCase()) {
            case "SEQUENTIAL":
                return AccessMode.SEQUENTIAL;
            case "RANDOM":
                return AccessMode.RANDOM;
            case "":
            case "QUERY":
                return AccessMode.QUERY;
            default:
                Logger.getInstance().warn(where + ": FetchType '" + fetchType + "' is not recognised"
                        + " - treating as Query. Use Sequential, Random or Query.");
                return AccessMode.QUERY;
        }
    }

    /**
     * Oracle thin URL from Host/Port/SID. A Host that is already a full JDBC URL
     * (starts with "jdbc:") is used as it is.
     */
    private String jdbcUrl(com.stubio.util.DataSource connection) {
        String host = trim(connection.getHost());
        if (host.toLowerCase().startsWith("jdbc:")) {
            return host;
        }
        if (host.isEmpty() || trim(connection.getPort()).isEmpty() || trim(connection.getSid()).isEmpty()) {
            throw new DataSourceException("Host, Port and SID are required for connection '"
                    + connection.getConnectionName() + "'");
        }
        return "jdbc:oracle:thin:@" + host + ":" + trim(connection.getPort()) + ":" + trim(connection.getSid());
    }

    private java.util.Properties connectionProperties(com.stubio.util.DataSource connection) {
        java.util.Properties properties = new java.util.Properties();
        if (connection.getGenProp() != null) {
            for (GenericProperty property : connection.getGenProp()) {
                // An empty <vs:ConProperty/> parses as a property with no key.
                if (property != null && property.getKey() != null && !property.getKey().isBlank()) {
                    properties.setProperty(property.getKey().trim(),
                            property.getValue() == null ? "" : property.getValue());
                }
            }
        }
        return properties;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> java.util.List<T> nullSafe(java.util.List<T> list) {
        return list == null ? java.util.Collections.emptyList() : list;
    }

    public boolean startService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if(service == null){
                Logger.getInstance().error("Service doesn't exist :"+serviceName);
                return false;
            }
            if(!service.isRunning()){
                service.start();
                service.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));
                Logger.getInstance().info("Service started successfully :"+ serviceName);
                Utility.getInstance().updateServiceStatusInDb(service, "Running");
                Utility.getInstance().updateServiceFromCatalogDb(serviceName,"ACTIVE", service.getConfig().getPort());

            }
            return true;

        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if(service == null){
                Logger.getInstance().error("Service doesn't exist :"+serviceName);
                return false;
            }

            service.stop();
            services.remove(service.getName(), service);
            //remove from db
            Utility.getInstance().deleteServiceFromDb(service.getName());
            //remove from directory
            CustomMethods.getInstance().removeXmlFile(service.getName());
            //update status incatalog db
            Utility.getInstance().deleteServiceFromCatalogDb(serviceName);
            Logger.getInstance().info("Service deleted successfully :"+ serviceName);
            return true;

        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
        }
        return false;
    }

    public boolean stopService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if (service == null){
                Logger.getInstance().error("Service doesn't exists :"+serviceName);
                return false;
            }
            if (service.isRunning()){
                service.stop();
                Logger.getInstance().info("Service stopped successfully :"+ serviceName);
                service.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));
                Utility.getInstance().updateServiceStatusInDb(service, "Stopped");
                Utility.getInstance().updateServiceFromCatalogDb(serviceName,"INACTIVE", service.getConfig().getPort());
                return true;
            }
        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
        }

        Logger.getInstance().error("Service is not running :"+ serviceName);
        return false;
    }
}

