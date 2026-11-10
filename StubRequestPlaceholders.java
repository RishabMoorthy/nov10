package org.framework.core.expansion;

import org.framework.utils.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${stub.request.<path>}} in a response body with values from the incoming
 * request body. REST and SOAP; not TCP.
 *
 * <h3>JSON request - dot path, arrays from 0</h3>
 * <pre>
 *   ${stub.request.customer.id}               nested field
 *   ${stub.request.customer.e-mail}           '-', '_' and spaces need no brackets
 *   ${stub.request.items[0].sku}              array item (from 0)
 *   ${stub.request.items[-1].sku}             last item
 *   ${stub.request.orders[0].lines[1].code}   array inside array
 *   ${stub.request[1].id}                     the body itself is an array
 *   ${stub.request.customer['first.name']}    brackets only for a key that contains a dot
 *   ${stub.request.customer}                  a whole object/array comes out as JSON text
 * </pre>
 *
 * <h3>XML / SOAP request - dot path (from 0) or XPath (from 1)</h3>
 * <pre>
 *   ${stub.request.Envelope.Body.getUser.id}            dot path from the root, prefixes optional
 *   ${stub.request.Envelope.Body.getUser.Phone[1]}      second repeated element (from 0)
 *   ${stub.request.Envelope.Body.getUser.Amount.@currency}   attribute
 *   ${stub.request.//order[2]/id}                       XPath: starts with '/' or '('
 *   ${stub.request.//order[@status='PAID']/id}          XPath condition
 *   ${stub.request.count(//order)}                      XPath function
 * </pre>
 * Namespace prefixes are ignored on both sides, so {@code //order/id} finds
 * {@code <ns:order><ns:id>}. When several elements match, the first one is used.
 *
 * <p>A value that cannot be found becomes empty and is logged. The value is inserted exactly
 * as it is - put quotes around the placeholder in a JSON response when the value is text.
 */
public final class StubRequestPlaceholders {

    public static final String PREFIX = "${stub.request";

    /** ${stub.request.<path>} or ${stub.request[<index>]...} - the path runs to the closing brace. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{stub\\.request(?:\\.([^}]*)|(\\[[^}]*))}");

    /** An XPath function call at the start, e.g. count(, string(, normalize-space( */
    private static final Pattern XPATH_FUNCTION = Pattern.compile("^[A-Za-z][A-Za-z0-9-]*\\s*\\(");

    private StubRequestPlaceholders() {
    }

    /**
     * Replaces every {@code ${stub.request...}} in {@code template}. Returns the template
     * unchanged when it has none.
     */
    public static String resolve(String template, String requestBody, String serviceName) {
        if (template == null || !template.contains(PREFIX)) {
            return template;
        }

        Source source = Source.of(requestBody, serviceName);
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1) != null ? m.group(1).trim() : m.group(2).trim();
            String value = source.lookup(path);
            if (value == null) {
                Logger.getInstance().warn("[stub.request] " + serviceName + ": '" + m.group(0)
                        + "' not found in the request - left empty"
                        + (isXPath(path) ? " (XPath counts from 1)" : " (dot path counts from 0)"));
                value = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean isXPath(String path) {
        return path.startsWith("/") || path.startsWith("(") || XPATH_FUNCTION.matcher(path).find();
    }

    // ---- the request body, parsed once per response ----

    private abstract static class Source {
        abstract String lookup(String path);

        static Source of(String body, String serviceName) {
            String trimmed = body == null ? "" : body.trim();
            try {
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    return new JsonSource(new JSONTokener(trimmed).nextValue());
                }
                if (trimmed.startsWith("<")) {
                    return new XmlSource(parseXml(trimmed));
                }
            } catch (Exception e) {
                Logger.getInstance().warn("[stub.request] " + serviceName
                        + ": request body is not valid JSON/XML - placeholders left empty (" + e.getMessage() + ")");
            }
            return new Source() {
                @Override
                String lookup(String path) {
                    return null;
                }
            };
        }
    }

    // ---- JSON ----

    private static final class JsonSource extends Source {
        private final Object root;

        JsonSource(Object root) {
            this.root = root;
        }

        @Override
        String lookup(String path) {
            Object node = root;
            for (Step step : steps(path)) {
                if (node == null) {
                    return null;
                }
                if (step.index != null) {
                    if (!(node instanceof JSONArray)) {
                        return null;
                    }
                    JSONArray array = (JSONArray) node;
                    int i = step.index < 0 ? array.length() + step.index : step.index;
                    node = i >= 0 && i < array.length() ? array.opt(i) : null;
                } else {
                    if (!(node instanceof JSONObject)) {
                        return null;
                    }
                    node = ((JSONObject) node).opt(step.name);
                }
            }
            if (node == null) {
                return null;
            }
            if (node == JSONObject.NULL) {
                return "null";
            }
            return node.toString();   // objects and arrays come out as JSON text
        }
    }

    // ---- XML ----

    private static final class XmlSource extends Source {
        private final Document doc;

        XmlSource(Document doc) {
            this.doc = doc;
        }

        @Override
        String lookup(String path) {
            return isXPath(path) ? xpath(path) : dotPath(path);
        }

        private String dotPath(String path) {
            List<Step> steps = steps(path);
            Element current = doc.getDocumentElement();
            int start = 0;
            // The path may name the root element (Envelope...) or start just below it.
            if (!steps.isEmpty() && steps.get(0).index == null && steps.get(0).attribute == null
                    && localName(steps.get(0).name).equals(current.getNodeName())) {
                start = 1;
                if (steps.size() > 1 && steps.get(1).index != null) {
                    if (steps.get(1).index != 0 && steps.get(1).index != -1) {
                        return null;
                    }
                    start = 2;
                }
            }

            for (int s = start; s < steps.size(); s++) {
                Step step = steps.get(s);
                if (step.attribute != null) {
                    if (s != steps.size() - 1) {
                        return null;
                    }
                    return current.hasAttribute(step.attribute) ? current.getAttribute(step.attribute) : null;
                }
                if (step.index != null) {
                    return null;   // an index always follows a name and is consumed with it
                }
                int wanted = 0;
                if (s + 1 < steps.size() && steps.get(s + 1).index != null) {
                    wanted = steps.get(s + 1).index;
                    s++;
                }
                current = child(current, localName(step.name), wanted);
                if (current == null) {
                    return null;
                }
            }
            return current.getTextContent() == null ? "" : current.getTextContent().trim();
        }

        private String xpath(String expression) {
            String expr = stripXPathPrefixes(expression);
            XPath xp = XPathFactory.newInstance().newXPath();
            try {
                NodeList nodes = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
                if (nodes.getLength() == 0) {
                    return null;
                }
                Node first = nodes.item(0);
                String text = first instanceof Attr ? ((Attr) first).getValue() : first.getTextContent();
                return text == null ? "" : text.trim();
            } catch (XPathExpressionException notANodeSet) {
                try {
                    // count(...), string(...), concat(...) etc.
                    String value = (String) xp.evaluate(expr, doc, XPathConstants.STRING);
                    if (value != null && value.endsWith(".0")) {
                        value = value.substring(0, value.length() - 2);
                    }
                    return value;
                } catch (XPathExpressionException e) {
                    Logger.getInstance().warn("[stub.request] invalid XPath '" + expression + "': " + e.getMessage());
                    return null;
                }
            }
        }

        private static Element child(Element parent, String name, int index) {
            List<Element> matches = new ArrayList<>();
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                    matches.add((Element) n);
                }
            }
            int i = index < 0 ? matches.size() + index : index;
            return i >= 0 && i < matches.size() ? matches.get(i) : null;
        }
    }

    // ---- shared: dot-path steps ----

    private static final class Step {
        String name;        // a field / element name
        Integer index;      // [n]
        String attribute;   // @name (XML only)
    }

    /**
     * Splits {@code a.b[0].c['x.y'].@attr} into steps. Only '.' and '[' separate - so '-',
     * '_' and spaces are part of a name. A quoted bracket holds a name that contains a dot.
     */
    static List<Step> steps(String path) {
        List<Step> steps = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                addName(steps, name);
                i++;
            } else if (c == '[') {
                addName(steps, name);
                int close = path.indexOf(']', i);
                if (close < 0) {
                    name.append(path.substring(i));
                    break;
                }
                String inside = path.substring(i + 1, close).trim();
                Step step = new Step();
                if ((inside.startsWith("'") && inside.endsWith("'") && inside.length() >= 2)
                        || (inside.startsWith("\"") && inside.endsWith("\"") && inside.length() >= 2)) {
                    step.name = inside.substring(1, inside.length() - 1);
                } else {
                    try {
                        step.index = Integer.parseInt(inside);
                    } catch (NumberFormatException e) {
                        step.name = inside;
                    }
                }
                steps.add(step);
                i = close + 1;
            } else {
                name.append(c);
                i++;
            }
        }
        addName(steps, name);
        return steps;
    }

    private static void addName(List<Step> steps, StringBuilder name) {
        String n = name.toString().trim();
        name.setLength(0);
        if (n.isEmpty()) {
            return;
        }
        Step step = new Step();
        if (n.startsWith("@")) {
            step.attribute = localName(n.substring(1));
        } else {
            step.name = n;
        }
        steps.add(step);
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    // ---- XML helpers: namespaces are ignored on both sides ----

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        dropPrefixes(doc, doc.getDocumentElement());
        return doc;
    }

    /** Renames {@code ns:order} to {@code order} and {@code xsi:type} to {@code type}. */
    private static void dropPrefixes(Document doc, Element element) {
        NamedNodeMap attrs = element.getAttributes();
        List<Attr> prefixed = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (a.getName().contains(":") && !a.getName().startsWith("xmlns")) {
                prefixed.add(a);
            }
        }
        for (Attr a : prefixed) {
            String local = localName(a.getName());
            element.removeAttributeNode(a);
            if (!element.hasAttribute(local)) {
                element.setAttribute(local, a.getValue());
            }
        }
        Element renamed = element.getNodeName().contains(":")
                ? (Element) doc.renameNode(element, null, localName(element.getNodeName()))
                : element;

        NodeList children = renamed.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                dropPrefixes(doc, (Element) children.item(i));
            }
        }
    }

    /** {@code //ns:order/@xsi:type} -> {@code //order/@type}; axes like child:: are kept. */
    private static String stripXPathPrefixes(String xpath) {
        return xpath.replaceAll("(?<![\\w.:-])[A-Za-z_][\\w.-]*:(?!:)(?=[A-Za-z_*])", "");
    }
}
