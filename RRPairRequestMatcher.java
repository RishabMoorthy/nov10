package org.framework.core;

import com.stubio.util.Argument;
import com.stubio.util.Request;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.Headers;

import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.utils.GroovyUtils;
import org.framework.utils.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates an RRPair's {@code vs:Request} match criteria
 * (RequestParameters / Headers / QueryParams) against a live MockRequest.
 *
 * <p>RRPair traversal/order is owned by ResponseResolver. This class only
 * decides whether the current RRPair's configured request criteria match.
 */
public final class RRPairRequestMatcher {

    private RRPairRequestMatcher() {
    }

    /**
     * Generic request matcher. All configured groups participate.
     */
    public static boolean matches(Request spec, Context context, MockRequest request) {
        if (spec == null) {
            return true;
        }

        Map<String, String> echoes = new LinkedHashMap<>();

        if (!matchGroup(spec.getRequestParameters(),
                name -> extractBodyField(request.getRequestContent(), name), echoes)) {
            return false;
        }

        if (!matchGroup(spec.getHeaders(),
                name -> extractHeader(request.getRequestHeaders(), name), echoes)) {
            return false;
        }

        if (!matchGroup(spec.getQueryParams(),
                name -> extractQueryParam(request.getQueryString(), name), echoes)) {
            return false;
        }

        // Capture echo values only after the whole RRPair has matched.
        context.getDynamicProperties().putAll(echoes);
        return true;
    }

    /**
     * Selective RRPair matching.
     *
     * <p>Only arguments selected in the VS configuration participate in
     * matching. Selected criteria may come from any of these groups:
     *
     * <ul>
     *   <li>RequestParameters - extracted from JSON/XML request body</li>
     *   <li>Headers - extracted from incoming HTTP headers</li>
     *   <li>QueryParams - extracted from the incoming query string</li>
     * </ul>
     *
     * <p>All selected criteria across all three groups must match. Unselected
     * criteria are ignored. Extra values present in the incoming request are
     * ignored.
     *
     * <p>A Selective RRPair with no selected criteria declines; it must not
     * become an accidental catch-all. Operation remains the explicit catch-all
     * MatchStyle.
     */
    public static boolean matchesSelective(Request spec, Context context, MockRequest request) {
        if (spec == null) {
            return false;
        }

        List<Argument> requestParameters = selectedOnly(spec.getRequestParameters());
        List<Argument> headers = selectedOnly(spec.getHeaders());
        List<Argument> queryParams = selectedOnly(spec.getQueryParams());

        // Nothing was selected anywhere -> this pair does not match.
        if (requestParameters.isEmpty() && headers.isEmpty() && queryParams.isEmpty()) {
            return false;
        }

        Map<String, String> echoes = new LinkedHashMap<>();

        if (!matchGroup(requestParameters,
                name -> extractBodyField(request.getRequestContent(), name), echoes)) {
            return false;
        }

        if (!matchGroup(headers,
                name -> extractHeader(request.getRequestHeaders(), name), echoes)) {
            return false;
        }

        if (!matchGroup(queryParams,
                name -> extractQueryParam(request.getQueryString(), name), echoes)) {
            return false;
        }

        // Do not leak partial echo values from a failed RRPair.
        context.getDynamicProperties().putAll(echoes);
        return true;
    }

    /**
     * Keeps only arguments that participate in matching.
     *
     * <p>Only an explicit {@code selected="false"} excludes an argument.
     * Missing/blank selected remains treated as selected for backward
     * compatibility with older .vs files.
     */
    private static List<Argument> selectedOnly(List<Argument> args) {
        List<Argument> selected = new ArrayList<>();
        if (args == null) {
            return selected;
        }

        for (Argument arg : args) {
            if (arg == null) {
                continue;
            }
            if (!"false".equalsIgnoreCase(trimToEmpty(arg.isSelected()))) {
                selected.add(arg);
            }
        }
        return selected;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean matchGroup(List<Argument> args,
                                      Function<String, String> extractor,
                                      Map<String, String> echoes) {
        if (args == null || args.isEmpty()) {
            return true;
        }

        for (Argument arg : args) {
            if (arg == null || arg.getName() == null || arg.getName().isBlank()) {
                continue;
            }

            String actual = extractor.apply(arg.getName());
            if (!compare(actual, arg.getValue(), arg.getMatchType(), arg.isCaseSensitive())) {
                return false;
            }

            if ("true".equalsIgnoreCase(arg.isEchoValue())) {
                echoes.put(arg.getName(), actual);
            }
        }
        return true;
    }

    /**
     * Resolves an argument name against JSON/XML request body.
     *
     * <p>Names beginning with '$' are treated as JSONPath. Names beginning
     * with '/' are treated as XPath. Bare names are resolved as '$.name' for
     * JSON and '//name' for XML.
     */
    private static String extractBodyField(String body, String name) {
        if (body == null || body.isBlank() || name == null || name.isBlank()) {
            return null;
        }

        String trimmed = body.trim();
        String path = name.trim();

        try {
            if (path.startsWith("$")) {
                Object value = JsonPath.parse(trimmed).read(path);
                return value == null ? null : value.toString();
            }

            if (path.startsWith("/")) {
                return new GroovyUtils(null).getXmlHolder(trimmed).getNodeValue(path);
            }

            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                Object value = JsonPath.parse(trimmed).read("$." + path);
                return value == null ? null : value.toString();
            }

            return new GroovyUtils(null).getXmlHolder(trimmed).getNodeValue("//" + path);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractHeader(Headers headers, String name) {
        return headers == null || name == null ? null : headers.getFirst(name);
    }

    /**
     * Returns the first matching query parameter value.
     * Both key and value are URL-decoded.
     */
    private static String extractQueryParam(String queryString, String name) {
        if (queryString == null || queryString.isBlank() || name == null) {
            return null;
        }

        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String decodedKey = decode(rawKey);

            if (!decodedKey.equals(name)) {
                continue;
            }

            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            return decode(rawValue);
        }

        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Supports both symbolic and textual comparison operators used by
     * existing VS files.
     */
    private static boolean compare(String actual,
                                   String expected,
                                   String matchType,
                                   String caseSensitiveAttr) {
        if (actual == null) {
            return false;
        }

        boolean caseSensitive = !"false".equalsIgnoreCase(caseSensitiveAttr);
        String a = caseSensitive ? actual : actual.toLowerCase();
        String e = expected == null ? null : (caseSensitive ? expected : expected.toLowerCase());

        switch (normalizeMatchType(matchType)) {
            case "NOT_EQUALS":
                return !Objects.equals(a, e);
            case "CONTAINS":
                return e != null && a.contains(e);
            case "STARTS_WITH":
                return e != null && a.startsWith(e);
            case "ENDS_WITH":
                return e != null && a.endsWith(e);
            case "REGEX":
                return matchesRegex(actual, expected, caseSensitive);
            case "GT":
            case "LT":
            case "GE":
            case "LE":
                return compareOrdered(a, e, actual, expected, normalizeMatchType(matchType));
            case "EQUALS":
            default:
                return Objects.equals(a, e);
        }
    }

    private static boolean compareOrdered(String a,
                                          String e,
                                          String actualRaw,
                                          String expectedRaw,
                                          String op) {
        Double actualNum = parseNumber(actualRaw);
        Double expectedNum = parseNumber(expectedRaw);

        int cmp = actualNum != null && expectedNum != null
                ? Double.compare(actualNum, expectedNum)
                : a.compareTo(e == null ? "" : e);

        switch (op) {
            case "GT":
                return cmp > 0;
            case "LT":
                return cmp < 0;
            case "GE":
                return cmp >= 0;
            case "LE":
                return cmp <= 0;
            default:
                return false;
        }
    }

    private static String normalizeMatchType(String matchType) {
        if (matchType == null) {
            return "EQUALS";
        }

        switch (matchType.trim().toLowerCase()) {
            case "=":
            case "==":
            case "equals":
                return "EQUALS";
            case "!=":
            case "not equals":
            case "notequals":
                return "NOT_EQUALS";
            case ">":
            case "greater than":
            case "greaterthan":
                return "GT";
            case "<":
            case "less than":
            case "lessthan":
                return "LT";
            case ">=":
            case "greater than or equals":
                return "GE";
            case "<=":
            case "less than or equals":
                return "LE";
            case "contains":
                return "CONTAINS";
            case "starts with":
            case "startswith":
                return "STARTS_WITH";
            case "ends with":
            case "endswith":
                return "ENDS_WITH";
            case "regex":
                return "REGEX";
            default:
                return "EQUALS";
        }
    }

    /**
     * Regex operator - same rule as the Stubio UI: the pattern only has to be found somewhere
     * in the value (use ^...$ to match the whole value). Case flag off = case-insensitive.
     * An invalid pattern never matches and is logged.
     */
    private static boolean matchesRegex(String actual, String pattern, boolean caseSensitive) {
        if (pattern == null) {
            return false;
        }
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            return Pattern.compile(pattern, flags).matcher(actual).find();
        } catch (PatternSyntaxException ex) {
            Logger.getInstance().warn("[RRPair] Regex operator has an invalid pattern '" + pattern
                    + "' - treated as no match: " + ex.getDescription());
            return false;
        }
    }

    private static Double parseNumber(String value) {
        if (value == null) {
            return null;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

