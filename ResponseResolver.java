package org.framework.core;

import com.stubio.util.DataGenerator;
import com.stubio.util.DataSourceSelect;
import com.stubio.util.Endpoint;
import com.stubio.util.Filter;
import com.stubio.util.GenericProperty;
import com.stubio.util.RRPair;
import com.stubio.util.Response;
import com.stubio.util.Script;
import com.stubio.util.StubOperation;
import com.stubio.util.TCPOperation;

import com.sun.net.httpserver.Headers;

import org.framework.config.ExecutionModeType;
import org.framework.core.expansion.StubRequestPlaceholders;
import org.framework.core.expansion.TemplateReplacer;
import org.framework.core.impl.GroovyScriptExecutor;
import org.framework.core.impl.GroovyScriptResponseGenerator;
import org.framework.datagenerator.DataGeneratorService;
import org.framework.datasource.DataSourceExecutor;
import org.framework.datasource.DataSourceService;
import org.framework.filters.FilterService;
import org.framework.genericproperties.PropertyService;
import org.framework.properties.Context;
import org.framework.properties.MatchContext;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.services.tcp.TcpFrameSpec;
import org.framework.utils.Logger;
import org.framework.utils.JsonXmlValueExtractor;
import org.framework.utils.RequestParser;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Response selection and post-processing for the com.stubio model.
 *
 * <p>
 * This holds the logic that used to live inside BaseRoute / RestRoute /
 * SoapRoute. The new model classes ({@link Endpoint}, {@link StubOperation})
 * are plain data holders, so the runtime state that a route used to carry -
 * the response generator, the MockResponse instances and the default response
 * name - is built once here at deploy time and keyed by the operation itself.
 *
 * <p>
 * Building the MockResponse list once (rather than per request) is deliberate:
 * Groovy scripts mutate the live MockResponse via setResponseContent() /
 * setBinaryData() and expect those edits to persist, exactly as they did when
 * BaseRoute owned the list.
 *
 * <p>
 * An Endpoint/StubOperation declares a chain of RRPairs, walked in XML document
 * order on every request. The first pair to <em>claim</em> the request wins and
 * nothing below it is evaluated. How a pair decides depends on its
 * {@link MatchStyle}:
 *
 * <ul>
 * <li><b>Script</b> - runs its MatchScript, which names a response through
 * {@code matchContext} and returns {@code true}, or returns {@code false} to
 * decline.</li>
 * <li><b>Selective</b> - every arg in {@code <vs:RequestParameters>} must be
 * present and match. Extra fields in the request are ignored; a missing listed
 * arg declines. Headers and QueryParams are not consulted.</li>
 * <li><b>Operation</b> - claims unconditionally. REST path + method and SOAP
 * operation name are resolved before the chain runs, so there is nothing left
 * to compare.</li>
 * </ul>
 *
 * <p>
 * The pair named by {@code defaultRR} is <b>held out of the walk entirely</b>
 * and answers only after every other pair has declined, so its position in the
 * file cannot affect matching. A pair that claims but cannot produce a response
 * declines instead of dead-ending the chain. When no pair claims and
 * {@code defaultRR} names nothing usable, the request gets a 404 rather than an
 * arbitrary response - the condition is warned about at deploy.
 */
public class ResponseResolver {

    /** Runtime state for a single RRPair within an Endpoint/StubOperation. */
    private static final class PairRuntime {
        RRPair pair;
        MatchStyle style;
        MatchScriptEvaluator scriptGenerator; // non-null only for MatchStyle=Script
        List<MockResponse> responses;
        String defaultResponseName;

        String id() {
            return pair == null || pair.getId() == null ? "(no id)" : pair.getId();
        }
    }

    /** Runtime state for one Endpoint or StubOperation: its RRPair chain. */
    private static final class OperationRuntime {
        /** The walk - every pair EXCEPT the defaultRR pair, in XML order. */
        List<PairRuntime> pairs = new ArrayList<>();
        /** Held back from the walk; used only after every pair declines. */
        PairRuntime defaultPair;
        String defaultRR;
        String contentType;
        List<DataGenerator> dataGenerators;
        List<Filter> filters;
        DataSourceSelect dataSourceSelect;
        /** "POST /api/v2" or the SOAP operation name - for data source log lines. */
        String label;

        /** The walk plus the held-back default - for response lookups by name. */
        List<PairRuntime> allPairs() {
            if (defaultPair == null) {
                return pairs;
            }
            List<PairRuntime> all = new ArrayList<>(pairs);
            all.add(defaultPair);
            return all;
        }
    }

    private final Map<Object, OperationRuntime> runtimes = new IdentityHashMap<>();
    private final DataGeneratorService dataGenerators = new DataGeneratorService();
    private final FilterService filterService = new FilterService();
    private final PropertyService propertyService = new PropertyService();

    /** Null when the service has no data sources. */
    private DataSourceExecutor dataSourceExecutor;

    /**
     * VS root-level Properties. They sit outside Endpoint/StubOperation in the
     * .vs file, so they are held per resolver rather than per operation.
     */
    private List<GenericProperty> genericProperties = new ArrayList<>();
    private final String serviceName;

    public ResponseResolver(String serviceName) {
        this.serviceName = serviceName;
    }

    // ---- registration (deploy time) ----

    public void register(Endpoint endpoint) {
        OperationRuntime runtime = new OperationRuntime();
        runtime.defaultRR = endpoint.getDefaultRR();
        runtime.contentType = "";
        runtime.dataGenerators = endpoint.getDataGenerators();
        runtime.filters = endpoint.getFilterList();
        runtime.dataSourceSelect = endpoint.getDataSourceSelect();
        runtime.label = endpoint.getMethod() + " " + endpoint.getPath();
        validateDataSources(runtime, pathVariables(endpoint.getPath()));

        for (RRPair pair : endpoint.getRrList()) {
            runtime.pairs.add(buildPairRuntime(pair, restResponses(pair, endpoint.getPath())));
        }

        holdBackDefaultPair(runtime, endpoint.getMethod() + " " + endpoint.getPath());
        runtimes.put(endpoint, runtime);
    }

    public void register(StubOperation operation) {
        OperationRuntime runtime = new OperationRuntime();
        runtime.defaultRR = operation.getDefaultRR();
        runtime.contentType = "Xml";
        runtime.dataGenerators = operation.getDataGenerators();
        runtime.filters = operation.getFilterList();
        runtime.dataSourceSelect = operation.getDataSourceSelect();
        runtime.label = operation.getName();
        validateDataSources(runtime, new LinkedHashSet<>());

        for (RRPair pair : operation.getRrList()) {
            runtime.pairs.add(buildPairRuntime(pair, soapResponses(pair)));
        }

        holdBackDefaultPair(runtime, operation.getName());
        runtimes.put(operation, runtime);
    }

    /**
     * A TCP operation. Same RRPair chain as SOAP; each response is sent as
     * Header + Body + Footer. {@link #setTcpFrameSpec} must be called first.
     */
    public void register(TCPOperation operation) {
        OperationRuntime runtime = new OperationRuntime();
        runtime.defaultRR = operation.getDefaultRR();
        runtime.contentType = "Tcp";
        runtime.dataGenerators = operation.getDataGenerators();
        runtime.filters = operation.getFilterList();
        runtime.dataSourceSelect = operation.getDataSourceSelect();
        runtime.label = operation.getName();
        validateDataSources(runtime, new LinkedHashSet<>());

        for (RRPair pair : operation.getRrList()) {
            runtime.pairs.add(buildPairRuntime(pair, tcpResponses(pair, operation.getName())));
        }

        holdBackDefaultPair(runtime, operation.getName());
        runtimes.put(operation, runtime);
    }

    /** The service's TCP Handler fields - used to frame live replies and fix header lengths. */
    public void setTcpFrameSpec(TcpFrameSpec tcpFrameSpec) {
        this.tcpFrameSpec = tcpFrameSpec;
    }

    private TcpFrameSpec tcpFrameSpec;

    /**
     * Per TCP response: its header length, footer length and template body length, so
     * placeholder expansion can keep the header's length field right.
     */
    private final Map<MockResponse, int[]> tcpLayouts = new IdentityHashMap<>();

    /**
     * Receives the service's data sources at deploy time. Must be called before
     * {@link #register(Endpoint)} / {@link #register(StubOperation)} so each
     * endpoint's data sources are loaded and checked as it registers.
     */
    public void setDataSourceService(DataSourceService dataSourceService) {
        this.dataSourceExecutor = dataSourceService == null
                ? null
                : new DataSourceExecutor(serviceName, dataSourceService);
    }

    private void validateDataSources(OperationRuntime runtime, Set<String> pathVariables) {
        if (dataSourceExecutor != null && runtime.dataSourceSelect != null) {
            dataSourceExecutor.validate(runtime.dataSourceSelect, runtime.label, pathVariables);
        }
    }

    /** The {name} segments of an endpoint path template. */
    private static Set<String> pathVariables(String path) {
        Set<String> names = new LinkedHashSet<>();
        if (path == null) {
            return names;
        }
        for (String part : path.split("/")) {
            if (part.startsWith("{") && part.endsWith("}") && part.length() > 2) {
                names.add(part.substring(1, part.length() - 1));
            }
        }
        return names;
    }

    private PairRuntime buildPairRuntime(RRPair pair, List<MockResponse> responses) {
        PairRuntime runtime = new PairRuntime();
        runtime.pair = pair;
        runtime.responses = responses;
        runtime.style = resolveStyle(pair);
        runtime.defaultResponseName = defaultResponseName(responses);
        runtime.scriptGenerator = runtime.style == MatchStyle.SCRIPT ? generatorFor(pair) : null;
        return runtime;
    }

    /**
     * An absent, unrecognised or {@code Signature} MatchStyle becomes
     * OPERATION - which claims unconditionally - so it is logged loudly at
     * deploy rather than passing silently.
     */
    private MatchStyle resolveStyle(RRPair pair) {
        String raw = MatchStyle.rawOf(pair);
        MatchStyle style = MatchStyle.parse(raw);
        if (style == null) {
            String id = pair.getId() == null ? "(no id)" : pair.getId();
            Logger.getInstance().warn("[RRPair] " + serviceName + " pair=" + id
                    + " MatchStyle " + (trimToEmpty(raw).isEmpty() ? "is absent" : "'" + raw + "' is not recognised")
                    + " - treating as Operation, which claims every request.");
            return MatchStyle.OPERATION;
        }
        return style;
    }

    /**
     * Removes the {@code defaultRR} pair from the walk and keeps it aside, so
     * its position in the file cannot affect matching - it answers only after
     * every other pair has declined. Also emits the deploy-time warnings for
     * configurations that would otherwise fail silently at request time.
     */
    private void holdBackDefaultPair(OperationRuntime runtime, String label) {
        String where = serviceName + " " + trimToEmpty(label);
        String defaultRR = trimToEmpty(runtime.defaultRR);

        for (PairRuntime pair : runtime.pairs) {
            if (pair.pair.getId() == null || pair.pair.getId().isBlank()) {
                Logger.getInstance().warn("[RRPair] " + where + " has a pair with no id"
                        + " - it can never be a defaultRR target.");
            } else if (pair.pair.getId().startsWith("default_") && !pair.pair.getId().equals(defaultRR)) {
                Logger.getInstance().warn("[RRPair] " + where + " pair=" + pair.id()
                        + " looks like a default (id prefix 'default_') but no defaultRR points at it"
                        + " - treating it as an ordinary pair.");
            }
            if (pair.style != MatchStyle.SCRIPT && pair.responses.size() > 1) {
                Logger.getInstance().warn("[RRPair] " + where + " pair=" + pair.id() + " style=" + pair.style
                        + " carries " + pair.responses.size() + " responses - only a Script pair chooses"
                        + " between responses; serving '" + pair.defaultResponseName + "'.");
            }
            if (pair.responses.isEmpty()) {
                Logger.getInstance().warn("[RRPair] " + where + " pair=" + pair.id()
                        + " has no responses - it will always decline.");
            }
        }

        if (defaultRR.isEmpty()) {
            Logger.getInstance().warn("[RRPair] " + where + " has no defaultRR"
                    + " - if every pair declines the request gets a 404.");
        } else {
            for (PairRuntime pair : runtime.pairs) {
                if (defaultRR.equals(pair.pair.getId())) {
                    runtime.defaultPair = pair;
                    break;
                }
            }
            if (runtime.defaultPair == null) {
                Logger.getInstance().warn("[RRPair] " + where + " defaultRR='" + defaultRR
                        + "' names no pair in this operation - nothing is held back,"
                        + " and if every pair declines the request gets a 404.");
            } else {
                runtime.pairs.remove(runtime.defaultPair);
            }
        }

        // An unconditional pair swallows everything after it, including the
        // fallback. Legitimate when it is last; a trap anywhere else.
        for (int i = 0; i < runtime.pairs.size(); i++) {
            PairRuntime pair = runtime.pairs.get(i);
            // A response-less Operation pair declines instead of claiming, so
            // it does not shadow anything.
            if (pair.style != MatchStyle.OPERATION || pair.responses.isEmpty()) {
                continue;
            }
            if (i < runtime.pairs.size() - 1) {
                Logger.getInstance().warn("[RRPair] " + where + " pair=" + pair.id()
                        + " is Operation (claims every request) but is not last - the "
                        + (runtime.pairs.size() - 1 - i) + " pair(s) after it are unreachable.");
            } else if (runtime.defaultPair != null) {
                Logger.getInstance().warn("[RRPair] " + where + " ends with Operation pair=" + pair.id()
                        + ", which claims every request - defaultRR='" + defaultRR + "' will never be reached.");
            }
        }
    }

    // ---- resolution (request time) ----

    /**
     * Receives the VS root-level Properties at deploy time. They are outside the
     * Endpoint / StubOperation in the .vs file, so RequestHandlerFactory hands
     * them to the resolver once, when the service is deployed.
     */
    public void setProperties(List<GenericProperty> genericProperties) {
        this.genericProperties = genericProperties == null
                ? new ArrayList<>()
                : new ArrayList<>(genericProperties);
    }

    /**
     * Was BaseRoute.getResponse(). {@code operation} is an Endpoint or a
     * StubOperation.
     */
    public MockResponse getResponse(Object operation, Context context, MockRequest request) throws Exception {
        context.setMockOperation(operation);

        // The three maps stay separate because Save Property Value to File names
        // its source explicitly through PropertyType. Properties and DataGenerators
        // are resolved first so a filter can reference either of them.
        Map<String, Object> propertyValues = executeProperties(context);
        Map<String, Object> dataGeneratorValues = executeDataGenerators(operation, context);
        Map<String, Object> filterValues =
                executeFilters(operation, context, request, propertyValues, dataGeneratorValues);
        // Data sources run after the filters, so a query or column mapping can use
        // a filter variable, and before the RRPair chain, so match scripts can use
        // the row.
        executeDataSources(operation, context, request, filterValues);

        MockResponse resp = getMockResponse(operation, context, request);
        return processResponse(operation, context, resp, request);
    }

    /** VS-level Properties for this request; also merged into the dynamic properties. */
    private Map<String, Object> executeProperties(Context context) {
        Map<String, Object> values = new HashMap<>();
        if (context == null || genericProperties == null || genericProperties.isEmpty()) {
            return values;
        }
        Map<String, Object> processed = propertyService.process(genericProperties);
        if (processed != null) {
            values.putAll(processed);
        }
        context.getDynamicProperties().putAll(values);
        return values;
    }

    /**
     * DataGenerator values for this request, merged into the dynamic properties so
     * the filters, the response template and the scripts all see the same values.
     */
    private Map<String, Object> executeDataGenerators(Object operation, Context context) {
        Map<String, Object> values = new HashMap<>();
        OperationRuntime runtime = runtimes.get(operation);
        if (runtime == null || runtime.dataGenerators == null || runtime.dataGenerators.isEmpty()) {
            return values;
        }
        Map<String, Object> generated = dataGenerators.generate(runtime.dataGenerators);
        if (generated != null) {
            values.putAll(generated);
        }
        if (context != null) {
            context.getDynamicProperties().putAll(values);
        }
        return values;
    }

    /**
     * Runs this operation's filters. The Property and DataGenerator maps are passed
     * in so Save Property Value to File can resolve whichever source its
     * PropertyType names - Filter, Properties, DataGenerator, Stub Request or
     * GlobalProperties.
     */
    private Map<String, Object> executeFilters(Object operation, Context context, MockRequest request,
            Map<String, Object> propertyValues, Map<String, Object> dataGeneratorValues) {
        Map<String, Object> values = new HashMap<>();
        OperationRuntime runtime = runtimes.get(operation);
        if (runtime == null || runtime.filters == null || runtime.filters.isEmpty()) {
            return values;
        }
        Map<String, Object> processed =
                filterService.process(runtime.filters, request, propertyValues, dataGeneratorValues);
        if (processed != null) {
            values.putAll(processed);
        }
        if (context != null) {
            context.getDynamicProperties().putAll(values);
        }
        return values;
    }

    /**
     * Picks one row from each of this operation's data sources and stores its
     * columns as {@code Name.COLUMN} in the dynamic properties.
     */
    private void executeDataSources(Object operation, Context context, MockRequest request,
            Map<String, Object> filterValues) {
        OperationRuntime runtime = runtimes.get(operation);
        if (dataSourceExecutor == null || runtime == null || runtime.dataSourceSelect == null) {
            return;
        }
        dataSourceExecutor.execute(runtime.dataSourceSelect, runtime.label, context, request, filterValues);
    }

    /**
     * Was BaseRoute.getMockResponse(). Walks the operation's RRPair chain in
     * document order; the first pair that matches wins.
     */
    public MockResponse getMockResponse(Object operation, Context context, MockRequest request)
            throws UnsupportedEncodingException {

        OperationRuntime runtime = runtimes.get(operation);
        if (runtime == null) {
            return null;
        }

        for (PairRuntime pair : runtime.pairs) {
            PairOutcome outcome = evaluatePair(pair, context, request);

            if (!outcome.isClaimed()) {
                log(pair, "declined, trying next");
                continue;
            }

            // Script-level escape hatch to force live invocation for this request.
            if ("LIVE".equals(outcome.getResponseName())) {
                log(pair, "claimed -> LIVE");
                return new MockResponse(this.serviceName, "LIVE");
            }

            MockResponse resp = responseFor(pair, outcome);
            if (resp != null) {
                log(pair, "claimed -> " + resp.getName());
                return resp;
            }

            // Claimed but produced nothing servable. Decline rather than
            // dead-ending the chain, so a later pair (or the default) can answer.
            log(pair, "claimed but has no usable response - declining, trying next");
        }

        return fallbackResponse(runtime);
    }

    /** Asks one pair whether it wants this request, according to its style. */
    private PairOutcome evaluatePair(PairRuntime pair, Context context, MockRequest request) {
        switch (pair.style) {
            case SCRIPT:
                return pair.scriptGenerator.evaluate(context, request, new MatchContext(pair.id()));

            case SELECTIVE:
                return RRPairRequestMatcher.matchesSelective(pair.pair.getRequest(), context, request)
                        ? PairOutcome.claimed()
                        : PairOutcome.declined();

            case OPERATION:
            default:
                // Path + method (REST) / operation name (SOAP) are already
                // resolved before the chain runs, so there is nothing left to
                // test - Operation claims unconditionally and ignores any args.
                return PairOutcome.claimed();
        }
    }

    /**
     * The response a claiming pair should serve: the one its match script named,
     * or its own single response. A script naming a response that is not in its
     * own ResponseSet is an error - the pair declines rather than quietly
     * serving something else.
     */
    private MockResponse responseFor(PairRuntime pair, PairOutcome outcome) {
        String named = outcome.getResponseName();
        if (named == null) {
            return findByName(pair.responses, pair.defaultResponseName);
        }
        MockResponse resp = findByName(pair.responses, named);
        if (resp == null) {
            Logger.getInstance().error("[RRPairChain] " + serviceName + " pair=" + pair.id()
                    + " match script named response '" + named
                    + "', which is not in its own ResponseSet - declining, chain continues.");
        }
        return resp;
    }

    private void log(PairRuntime pair, String what) {
        Logger.getInstance().info("[RRPairChain] " + serviceName + " pair=" + pair.id()
                + " style=" + pair.style + " " + what);
    }

    private MockResponse findByName(List<MockResponse> responses, String name) {
        if (name == null) {
            return null;
        }
        for (MockResponse resp : responses) {
            if (resp.getName().equals(name)) {
                return resp;
            }
        }
        return null;
    }

    /**
     * Same shape as the "no endpoint/operation matched" 404 in
     * MockRequestHandler/SoapRequestHandler, for the case where an
     * endpoint/operation matched but its RRPair chain had nothing to serve.
     */
    private MockResponse noResponseConfigured() {
        Headers headers = new Headers();
        headers.add("content-type", "plain/text");
        String out = "No response is configured for this request";
        return new MockResponse("No Response Configured", headers, 404,
                out.getBytes(StandardCharsets.UTF_8), "", "", serviceName);
    }

    /**
     * Every pair declined - serve the held-back defaultRR pair.
     *
     * <p>
     * The default pair is resolved, not evaluated: it sits outside the matching
     * flow, so a Script-style default does not get its MatchScript run here.
     *
     * <p>
     * When defaultRR is blank or names no pair there is deliberately no second
     * guess. Serving some other pair's first response would hide a broken
     * configuration behind plausible output; the 404 guard is the honest answer
     * and the condition was already warned about at deploy.
     */
    private MockResponse fallbackResponse(OperationRuntime runtime) {
        if (runtime.defaultPair == null) {
            Logger.getInstance().warn("[RRPairChain] " + serviceName
                    + " every pair declined and defaultRR=" + quoted(runtime.defaultRR)
                    + " resolves to no pair - returning 404.");
            return null;
        }

        MockResponse resp = findByName(runtime.defaultPair.responses, runtime.defaultPair.defaultResponseName);
        if (resp == null) {
            Logger.getInstance().warn("[RRPairChain] " + serviceName + " every pair declined and default pair="
                    + runtime.defaultPair.id() + " has no response - returning 404.");
            return null;
        }

        Logger.getInstance().info("[RRPairChain] " + serviceName + " every pair declined -> defaultRR="
                + runtime.defaultPair.id() + " -> " + resp.getName());
        return resp;
    }

    private static String quoted(String value) {
        return value == null || value.isBlank() ? "(absent)" : "'" + value.trim() + "'";
    }

    /** Was BaseRoute.processResponse(). */
    public MockResponse processResponse(Object operation, Context context, MockResponse resp, MockRequest request)
            throws Exception {

        // getMockResponse() returns null when the endpoint/operation matched but its
        // RRPair chain produced nothing usable (no pair matched, and none had a
        // fallback default). Without this, LiveInvocation.getLiveResponse() and the
        // plain-mock path below both dereference resp directly and NPE instead of
        // giving the caller a real HTTP response.
        if (operation instanceof TCPOperation) {
            return processTcpResponse(operation, context, resp, request);
        }

        if (resp == null) {
            resp = noResponseConfigured();
        }

        ExecutionModeType mode = context.getMockService().getConfig().getExecutionModeType();

        if (mode.callsLiveServer()) {

            LiveInvocation.LiveResult live = new LiveInvocation()
                    .invoke(context, request, resp, mode.fallsBackToMock());

            if (live.isLiveAnswered()) {
                // The destination server answered - that response is the answer, whatever
                // its status. True for both Live Invocation and Failover.
                return live.getResponse();
            }

            if (!mode.fallsBackToMock()) {
                // Live Invocation never consults the mock response, so the failure is the
                // answer the caller gets.
                return live.getResponse();
            }

            Logger.getInstance().info("[ExecutionMode] Failover: live call failed ("
                    + live.getFailureReason() + "); serving the virtual service response instead.");
            // fall through to the mock path below
        }

        return renderMockResponse(operation, context, resp, request);
    }

    /**
     * TCP version of {@link #processResponse}: same three modes, but the live call is a socket
     * call ({@link TcpLiveInvocation}) and the reply is framed with the TCP Handler fields.
     *
     * <ul>
     *   <li>Stand In / none - the matched response.</li>
     *   <li>Live Invocation - the live reply; when the call fails, the error text.</li>
     *   <li>Failover - the live reply; when the call fails, the matched response.</li>
     * </ul>
     * A match script can also force one request live with {@code matchContext.setResponse("LIVE")}.
     */
    private MockResponse processTcpResponse(Object operation, Context context, MockResponse resp,
            MockRequest request) throws Exception {

        ExecutionModeType mode = context.getMockService().getConfig().getExecutionModeType();
        boolean scriptAskedForLive = resp != null && "LIVE".equals(resp.getName()) && resp.getResponseBytes() == null;

        if (mode.callsLiveServer() || scriptAskedForLive) {
            TcpFrameSpec spec = tcpFrameSpec != null
                    ? tcpFrameSpec
                    : context.getMockService().getConfig().getTcpFrameSpec();
            LiveInvocation.LiveResult live = new TcpLiveInvocation()
                    .invoke(context, request, spec, mode.fallsBackToMock());

            if (live.isLiveAnswered() || !mode.fallsBackToMock()) {
                return live.getResponse();
            }
            Logger.getInstance().info("[ExecutionMode] Failover: TCP live call failed ("
                    + live.getFailureReason() + "); serving the virtual service response instead.");
        }

        if (resp == null || resp.getResponseBytes() == null) {
            return tcpNoResponseConfigured();
        }
        return renderMockResponse(operation, context, resp, request);
    }

    private MockResponse tcpNoResponseConfigured() {
        return new MockResponse("No Response Configured", null, 404,
                "No response is configured for this request".getBytes(TcpFrameSpec.CHARSET), "", "", serviceName);
    }

    /**
     * Turns a matched mock response into the bytes sent back: response script, date
     * expressions, then property and request placeholders.
     *
     * <p>Split out of processResponse so Failover can reach it after a failed live call.
     */
    private MockResponse renderMockResponse(Object operation,
            Context context,
            MockResponse resp,
            MockRequest request) throws Exception {

        if (resp.getScript() != null && !resp.getScript().isEmpty()) {
            GroovyScriptExecutor groovyExecutor = new GroovyScriptExecutor();
            HashMap<String, Object> configureResponseScriptProperties = new HashMap<>();
            configureResponseScriptProperties.put("context", context);
            configureResponseScriptProperties.put("mockResponse", resp);
            groovyExecutor.executeScript(resp.getScript(), configureResponseScriptProperties, context);
        }

        if (operation instanceof TCPOperation) {
            // ISO-8859-1 keeps every byte as configured; UTF-8 would alter non-ASCII bytes.
            int[] layout = tcpLayouts.get(resp);
            String rendered = resolveDateExpressions(new String(resp.getResponseBytes(), TcpFrameSpec.CHARSET));
            rendered = resolvePlaceholders(rendered, operation, context, request);
            rendered = fixTcpHeaderLength(rendered, layout);
            resp = resp.clone();
            resp.setResponseBytes(rendered.getBytes(TcpFrameSpec.CHARSET));
            return resp;
        }

        String resolvedBody = resolveDateExpressions(new String(resp.getResponseBytes(), "UTF-8"));
        resolvedBody = resolvePlaceholders(resolvedBody, operation, context, request);
        resp = resp.clone();
        resp.setResponseBytes(resolvedBody.getBytes(StandardCharsets.UTF_8));
        return resp;
    }

    /**
     * When a ${...} placeholder changed the body length, rewrites the header's length field
     * so the client still frames the reply correctly. Only done when the header's field held
     * the template body length - a header that was not carrying the body length is left alone.
     */
    private String fixTcpHeaderLength(String rendered, int[] layout) {
        if (layout == null || tcpFrameSpec == null || !tcpFrameSpec.isFramed()) {
            return rendered;
        }
        int headerLen = layout[0];
        int footerLen = layout[1];
        int templateBodyLen = layout[2];
        if (headerLen <= 0 || rendered.length() < headerLen + footerLen) {
            return rendered;
        }
        String header = rendered.substring(0, headerLen);
        int bodyLen = rendered.length() - headerLen - footerLen;
        if (bodyLen == templateBodyLen || tcpFrameSpec.lengthFieldOf(header) != templateBodyLen) {
            return rendered;
        }
        String fixed = tcpFrameSpec.withBodyLength(header, bodyLen);
        Logger.getInstance().info("[TCP] " + serviceName + " body length changed " + templateBodyLen + " -> "
                + bodyLen + " after placeholders; header length field updated");
        return fixed + rendered.substring(headerLen);
    }

    /** Was BaseRoute.resolvePlaceholders(). */
    private String resolvePlaceholders(String responseBody, Object operation, Context context,
            MockRequest request) throws Exception {
        TemplateReplacer replacer = new TemplateReplacer();
        String contentType;
        if (responseBody.startsWith("<")) {
            contentType = "Xml";
        } else {
            contentType = "Json";
        }
        System.out.println("content type " + contentType);
        if (responseBody.contains("#MockResponse#Request")) {
            storeMagicStringProperties(responseBody, contentType, context, request);
        }
        // ${stub.request.<path>} - REST and SOAP only; a TCP request is TLV, not JSON/XML.
        if (!(operation instanceof TCPOperation)) {
            responseBody = StubRequestPlaceholders.resolve(responseBody,
                    request == null ? null : request.getRequestContent(), serviceName);
        }
        replacer.addPropertyMap("MockService", context.getMockService().getMockServiceProperties());

        // DataGenerator, Property and Filter values all share the unnamed map with
        // the dynamic properties so a response can use the plain ${VariableName}
        // form. TemplateReplacer keys its maps by name, so these must be merged
        // rather than added twice - a second addPropertyMap("") would replace the
        // dynamic properties.
        //
        // getResponse() has already generated the DataGenerator values for this
        // request and merged them into the dynamic properties. Do NOT generate them
        // again here: a Sequential Number generator would advance twice per request,
        // and the value written to a file by a filter would not be the value the
        // response carries.
        Map<String, Object> unnamed = new HashMap<>(context.getDynamicProperties());
        replacer.addPropertyMap("", unnamed);

        return replacer.replaceTemplate(responseBody);
    }

    /** Was BaseRoute.storeMagicStringProperties(). */
    private void storeMagicStringProperties(String responseBody, String contentType, Context context,
            MockRequest request) throws Exception {
        List<Object> valueList = null;
        if (contentType.equals("Json")) {
            valueList = JsonXmlValueExtractor.extractValuesFromJson(responseBody);
        }
        if (contentType.equals("Xml")) {
            valueList = JsonXmlValueExtractor.extractValuesFromXml(responseBody);
        }
        RequestParser parser = new RequestParser();
        for (Object property : valueList) {
            String propertyString = property.toString();
            Object value = parser.getRequestValue(request.getRequestContent(), propertyString);
            context.setProperty(propertyString.substring(2, propertyString.indexOf("}")), value);
        }
    }

    /** Was BaseRoute.resolveDateExpressions(). */
    public static String resolveDateExpressions(String json) {
        Pattern pattern = Pattern.compile(
                "\\$\\{=new java\\.text\\.SimpleDateFormat\\(\\\"([^\\\"]+)\\\"\\)\\.format\\(new java\\.util\\.Date\\(\\)\\)\\}");
        Matcher matcher = pattern.matcher(json);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String format = matcher.group(1);
            try {
                String formattedDate = new SimpleDateFormat(format).format(new Date());
                matcher.appendReplacement(sb, Matcher.quoteReplacement(formattedDate));
            } catch (Exception e) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("ERROR_DATE_FORMAT"));
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    // ---- accessors kept for parity with the old route API ----

    /** Aggregates responses across every RRPair in the operation's chain. */
    public List<MockResponse> getResponses(Object operation) {
        OperationRuntime runtime = runtimes.get(operation);
        if (runtime == null) {
            return new ArrayList<>();
        }
        List<MockResponse> all = new ArrayList<>();
        for (PairRuntime pair : runtime.allPairs()) {
            all.addAll(pair.responses);
        }
        return all;
    }

    public MockResponse getMockResponseByName(Object operation, String mockResponseName) {
        for (MockResponse response : getResponses(operation)) {
            if (response.getName().equals(mockResponseName)) {
                return response;
            }
        }
        return null;
    }

    /** SCRIPT if any RRPair in the chain is Script-style, SEQUENCE otherwise. */
    public String getDispatchStyle(Object operation) {
        OperationRuntime runtime = runtimes.get(operation);
        if (runtime == null) {
            return "SEQUENCE";
        }
        for (PairRuntime pair : runtime.allPairs()) {
            if (pair.scriptGenerator != null) {
                return "SCRIPT";
            }
        }
        return "SEQUENCE";
    }

    // ---- building runtime state from the com.stubio model ----

    private MatchScriptEvaluator generatorFor(RRPair pair) {
        String script = pair.getResponseSelection().getMatchScript() == null
                ? ""
                : trimToEmpty(pair.getResponseSelection().getMatchScript().getScript());
        return new GroovyScriptResponseGenerator(script);
    }

    /**
     * The response a non-Script pair serves once it claims: its first (and, per
     * the contract, only) response.
     *
     * <p>
     * {@code <vs:RRPair defaultResponse="...">} is deliberately not read. The
     * per-pair default was removed from the contract - keeping it would let a
     * pair that declines still contribute a response, which is what made every
     * pair with a ResponseSet behave like a catch-all. The attribute still
     * exists in the model and in some exported files; it is simply ignored.
     */
    private String defaultResponseName(List<MockResponse> responses) {
        return responses.isEmpty() ? null : responses.get(0).getName();
    }

    /**
     * REST responses carry headers, the endpoint path and the service name so
     * that runtime edits from Groovy can be written back.
     */
    private List<MockResponse> restResponses(RRPair pair, String path) {
        List<MockResponse> mockResponses = new ArrayList<>();
        if (pair.getResponseSet() == null) {
            return mockResponses;
        }
        for (Response response : pair.getResponseSet()) {
            mockResponses.add(new MockResponse(
                    trimToEmpty(response.getName()),
                    responseHeaders(response),
                    response.getStatusCode(),
                    bodyBytes(response),
                    scriptOf(response.getResponseScript()),
                    path,
                    serviceName));
        }
        return mockResponses;
    }

    /**
     * SOAP keeps the header-less construction the old SoapXmlParser used, so
     * SoapService's own content-type defaulting is unaffected.
     */
    private List<MockResponse> soapResponses(RRPair pair) {
        List<MockResponse> mockResponses = new ArrayList<>();
        if (pair.getResponseSet() == null) {
            return mockResponses;
        }
        for (Response response : pair.getResponseSet()) {
            mockResponses.add(new MockResponse(
                    trimToEmpty(response.getName()),
                    response.getStatusCode(),
                    bodyBytes(response),
                    scriptOf(response.getResponseScript())));
        }
        return mockResponses;
    }

    /**
     * TCP responses are sent as Header + Body + Footer, byte for byte. Unlike REST/SOAP the
     * parts are not trimmed - spaces can be part of a fixed-width header. Only line breaks
     * around each part (from editing the .vs by hand) are dropped.
     */
    private List<MockResponse> tcpResponses(RRPair pair, String operationName) {
        List<MockResponse> mockResponses = new ArrayList<>();
        if (pair.getResponseSet() == null) {
            return mockResponses;
        }
        for (Response response : pair.getResponseSet()) {
            String header = stripLineBreaks(response.getHeader());
            String body = stripLineBreaks(response.getBody());
            String footer = stripLineBreaks(response.getFooter());

            MockResponse mock = new MockResponse(
                    trimToEmpty(response.getName()),
                    null,
                    response.getStatusCode(),
                    (header + body + footer).getBytes(TcpFrameSpec.CHARSET),
                    scriptOf(response.getResponseScript()),
                    operationName,
                    serviceName);
            tcpLayouts.put(mock, new int[] { header.length(), footer.length(), body.length() });

            if (tcpFrameSpec != null && tcpFrameSpec.isFramed() && header.length() == tcpFrameSpec.getHeaderLength()) {
                int declared = tcpFrameSpec.lengthFieldOf(header);
                if (declared >= 0 && declared != body.length()) {
                    Logger.getInstance().warn("[TCP] " + serviceName + " " + operationName + " response '"
                            + mock.getName() + "': header says body length " + declared + " but the body is "
                            + body.length() + " characters - the client may frame it wrongly.");
                }
            }
            mockResponses.add(mock);
        }
        return mockResponses;
    }

    private static String stripLineBreaks(String value) {
        if (value == null) {
            return "";
        }
        int from = 0;
        int to = value.length();
        while (from < to && (value.charAt(from) == '\n' || value.charAt(from) == '\r')) {
            from++;
        }
        while (to > from && (value.charAt(to - 1) == '\n' || value.charAt(to - 1) == '\r')) {
            to--;
        }
        return value.substring(from, to);
    }

    private Headers responseHeaders(Response response) {
        Headers headers = new Headers();

        String contentType = trimToEmpty(response.getContentType());
        if (contentType.isEmpty()) {
            contentType = "application/json";
        }
        if (!contentType.toLowerCase().contains("charset")) {
            contentType += "; charset=UTF-8";
        }
        headers.add("Content-Type", contentType);

        if (response.getCustomHeaders() != null) {
            for (GenericProperty header : response.getCustomHeaders()) {
                if (header.getKey() != null && !header.getKey().isBlank()) {
                    headers.add(header.getKey(), trimToEmpty(header.getValue()));
                }
            }
        }

        return headers;
    }

    private byte[] bodyBytes(Response response) {
        return trimToEmpty(response.getBody()).getBytes(StandardCharsets.UTF_8);
    }

    private String scriptOf(Script script) {
        return script == null ? "" : trimToEmpty(script.getScript());
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

