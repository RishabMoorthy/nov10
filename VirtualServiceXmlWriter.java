package com.stubio.util;

import java.io.File;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class VirtualServiceXmlWriter {

    private static final String VS_PREFIX = "vs";
    private static final String VS_NS = "http://wu.com/stubio/config";

    public void saveToXml(VirtualServiceObject vso) throws Exception {
        Document doc = buildDocument(vso);
        writeDocument(doc, vso.getXmlPath());
    }

    public Document buildDocument(VirtualServiceObject vso) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();

        // Root
        Element root = doc.createElementNS(VS_NS, VS_PREFIX + ":stubVirtualService");
        root.setAttribute("xmlns:" + VS_PREFIX, VS_NS);
        doc.appendChild(root);

        // Root attributes
        attr(root, "name", safe(vso.getVsName()));
        attr(root, "projectId", safe(vso.getId()));
        attr(root, "createdVersion", safe(vso.getCreatedVersion()));
        attr(root, "lastUpdated", safe(LocalDate.now().toString()));
        attr(root, "encryption", safe(vso.getEncryption()));
        attr(root, "environment", safe(vso.getEnvironment()));

        appendProperties(doc, root, vso);
        appendDataSources(doc, root, vso);
        appendExecutionMode(doc, root, vso.getExeMode());
        appendDefaultError(doc, root, vso.getDefaultErrorResponse());

        // Services
        if ("rest".equalsIgnoreCase(safe(vso.getVsType())) && vso.getRestService() != null) {
            appendRestService(doc, root, vso);

        } else if ("soap".equalsIgnoreCase(safe(vso.getVsType())) && vso.getSoapService() != null) {
            appendSoapService(doc, root, vso); // Optional: implement parity if you need SOAP
        }
        else if ("tcp".equalsIgnoreCase(safe(vso.getVsType())) && vso.getTcpService() != null) {
            appendTCPService(doc, root, vso); // Optional: implement parity if you need SOAP
        }

        // ✅ Custom Scripts INSIDE SOAP (IMPORTANT)
        appendCustomScripts(doc, root, vso);

        // ✅ Config INSIDE SOAP (NOT root)
        appendConfig(doc, root, vso);

        // Tail
        root.appendChild(el(doc, "Metadata"));

        return doc;
    }

    private void appendProperties(Document doc, Element parent, VirtualServiceObject vso) {
        LinkedList<GenericProperty> vars = vso.getPropertyList();
        if (vars == null || vars.isEmpty())
            return;

        Element cfg = el(doc, "Properties");

        for (GenericProperty p : vars) {
            Element var = el(doc, "Variable");
            text(var, "Key", p.getKey());

            text(var, "Value", p.getValue());

            cfg.appendChild(var);
        }

        parent.appendChild(cfg);
    }

    private void appendDataSources(Document doc, Element parent, VirtualServiceObject vso) {
        LinkedHashMap<String, DataSource> map = vso.getDataSourceList();
        if (map == null || map.isEmpty())
            return;

        Element dss = el(doc, "DataSources");

        for (Map.Entry<String, DataSource> entry : map.entrySet()) {
            DataSource ds = entry.getValue();

            Element d = el(doc, "DataSource");
            text(d, "ConnectionName", ds.getConnectionName());
            text(d, "Driver", ds.getDriver());
            text(d, "Host", ds.getHost());
            text(d, "Port", ds.getPort());
            text(d, "SID", ds.getSid());
            text(d, "User", ds.getUser());
            text(d, "Password", ds.getPwd());

            // ConProperty (sample has empty Key/Value)
            Element cp = el(doc, "ConProperty");
            textOrEmpty(cp, "Key", null);
            textOrEmpty(cp, "Value", null);
            d.appendChild(cp);

            dss.appendChild(d);
        }

        parent.appendChild(dss);
    }

    private void appendRestService(Document doc, Element parent, VirtualServiceObject vso) {
        RestService rs = vso.getRestService();
        Element svc = el(doc, "RestService");
        attr(svc, "host", safe(vso.getHost()));
        if (vso.getPort() > 0)
            attr(svc, "port", String.valueOf(vso.getPort()));
        attr(svc, "contextPath", safe(vso.getContextPath()));
        attr(svc, "autoRestart", String.valueOf(vso.isAutoStart()));
        // If vso.hasAllowFallback() not present, set a default or comment this out
        attr(svc, "allowFallback", String.valueOf(vso.isAllowFallback()));
        attr(svc, "isSecured", String.valueOf(vso.isSecured()));

        appendSecuritySettings(doc, svc, rs.getSecuritySettings());

        appendEndpoints(doc, svc, rs.getEndpoints());

        parent.appendChild(svc);
    }

    private void appendExecutionMode(Document doc, Element parent, ExecutionMode em) {

        Element emEl = el(doc, "ExecutionMode");

        // ExecutionModeValue (explicit empty tag if missing)
        if (em != null && nonEmpty(em.getExeModeValue())) {
            text(emEl, "ExecutionModeValue", em.getExeModeValue());
        } else {
            emEl.appendChild(el(doc, "ExecutionModeValue"));
        }

        Element liveURLsEl = el(doc, "LiveURLs");

        if (em != null && em.getLiveURLs() != null && !em.getLiveURLs().isEmpty()
                && em.getLiveURLs().stream().noneMatch(ExecutionMode.LiveURL::isActive)) {
            em.getLiveURLs().get(0).setActive(true);
        }

        if (em != null && em.getLiveURLs() != null && !em.getLiveURLs().isEmpty()) {

            for (ExecutionMode.LiveURL lu : em.getLiveURLs()) {

                Element liveURLEl = el(doc, "LiveURL");

                // ✅ NEW: active attribute
                // Always write it for clarity and round-trip safety

                liveURLEl.setAttribute("active", String.valueOf(lu.isActive()));

                // Child elements
                textOrEmpty(liveURLEl, "EnvType", lu.getEnvType());
                textOrEmpty(liveURLEl, "TransType", lu.getTransportType());
                textOrEmpty(liveURLEl, "Host", lu.getHost());

                if (lu.getPort() != null) {
                    text(liveURLEl, "Port", String.valueOf(lu.getPort()));
                }

                textOrEmpty(liveURLEl, "BasePath", lu.getBasePath());

                liveURLsEl.appendChild(liveURLEl);
            }

        } else {
            // Keep empty LiveURLs element (schema-friendly)
            emEl.appendChild(liveURLsEl);
            parent.appendChild(emEl);
            return;
        }

        emEl.appendChild(liveURLsEl);
        parent.appendChild(emEl);
    }

    private void appendSecuritySettings(Document doc, Element parent, SecuritySettings ss) {
        if (ss == null)
            return;

        Element sec = el(doc, "SecuritySettings");

        KeyStore ks = ss.getKeyStoreMap() != null ? ss.getKeyStoreMap().get("KeyStore") : null;
        KeyStore ts = ss.getKeyStoreMap() != null ? ss.getKeyStoreMap().get("TrustStore") : null;

        if (ks != null) {
            Element k = el(doc, "Keystore");
            text(k, "Path", ks.getPath());
            text(k, "Type", ks.getType());
            text(k, "Password", ks.getKeyPass());
            text(k, "KeyAlias", ks.getKeyAlias());
            sec.appendChild(k);
        }
        if (ts != null) {
            Element t = el(doc, "TrustStore");
            text(t, "Path", ts.getPath());
            text(t, "Type", ts.getType());
            text(t, "Password", ts.getKeyPass());
            sec.appendChild(t);
        }

        text(sec, "ClientAuth", String.valueOf(ss.isClientAuth()));
        text(sec, "ServerAuth", String.valueOf(ss.isServerAuth()));

        parent.appendChild(sec);
    }

    private void appendDefaultError(Document doc, Element parent, DefaultErrorResponse der) {
        if (der == null)
            return;

        Element def = el(doc, "DefaultError");
        Element r = el(doc, "Response");

        attr(r, "statusCode", safe(der.getStatusCode()));
        attr(r, "statusMsg", safe(der.getStatusMessage()));
        attr(r, "contentType", safe(der.getContentType()));
        attr(r, "responseDelay", safe(der.getResponseDelay()));

        if (der.getHeaderList() != null && !der.getHeaderList().isEmpty()) {
            Element hs = el(doc, "Headers");
            for (GenericProperty gp : der.getHeaderList()) {
                Element h = el(doc, "Header");
                text(h, "Key", gp.getKey());
                text(h, "Value", gp.getValue());
                h.normalize();
                hs.appendChild(h);
            }
            r.appendChild(hs);
        }

        cdata(r, "body", der.getResponse());

        def.appendChild(r);
        parent.appendChild(def);
    }

    private void appendEndpoints(Document doc, Element parent, LinkedList<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty())
            return;

        Element eps = el(doc, "Endpoints");

        for (Endpoint ep : endpoints) {
            Element e = el(doc, "Endpoint");
            attr(e, "name", safe(ep.getName()));
            attr(e, "path", safe(ep.getPath()));
            attr(e, "method", safe(ep.getMethod()));
            attr(e, "defaultRR", safe(ep.getDefaultRR()));

            appendDataSourceSelect(doc, e, ep.getDataSourceSelect());
            appendDataGenerators(doc, e, ep.getDataGenerators());
            appendFilters(doc, e, ep.getFilterList());
            appendRequestData(doc, e, ep.getRequestList());
            appendRRPairs(doc, e, ep.getRrList());

            eps.appendChild(e);
        }

        parent.appendChild(eps);
    }

    private void appendRequestData(Document doc, Element parent, LinkedHashMap<String, Request> requestList) {

        Element cfg = el(doc, "RequestData");

        // cfg.appendChild(varsEl);
        Set<String> keySet = requestList.keySet();
        for (String p : keySet) {
            Request req = requestList.get(p);
            Element var = el(doc, "Request");
            text(var, "ContentType", req.getContentType());
            text(var, "Body", req.getRequestData());
            if (nonEmpty(req.getTagLength()))
                text(var, "TagLength", req.getTagLength());
            if (nonEmpty(req.getLengthSize()))
                text(var, "LengthSize", req.getLengthSize());

            textOrEmpty(var, "Name", p);

            cfg.appendChild(var);
        }

        parent.appendChild(cfg);
    }

    private void appendFilters(Document doc, Element parent, LinkedList<Filter> filterList) {

        Element varsEl = el(doc, "Filters");

        if (filterList == null || filterList.isEmpty()) {
            parent.appendChild(varsEl); // ✅ produces <Filters/>
            return;
        }

        for (Filter p : filterList) {
            Element var = el(doc, "Variable");

            text(var, "Type", p.getFilterType());
            text(var, "Name", p.getFilterName());

            textOrEmpty(var, "RequestName", p.getRequestName());
            textOrEmpty(var, "Path", p.getPath());
            textOrEmpty(var, "DateFormat", p.getDateFormat());
            textOrEmpty(var, "Offset", p.getOffSet());
            textOrEmpty(var, "StartText", p.getStartText());
            textOrEmpty(var, "EndText", p.getEndText());
            textOrEmpty(var, "FilePath", p.getFilePath());
            textOrEmpty(var, "PropertyType", p.getPropertyType());
            textOrEmpty(var, "PropertyName", p.getPropertyName());
            textOrEmpty(var, "AppendMode", String.valueOf(p.isAppendMode()));

            varsEl.appendChild(var);
        }

        parent.appendChild(varsEl);

    }

    private void appendRRPairs(Document doc, Element parent, LinkedList<RRPair> list) {
        if (list == null || list.isEmpty())
            return;

        for (RRPair rr : list) {
            Element r = el(doc, "RRPair");
            attr(r, "id", safe(rr.getId()));
            attr(r, "defaultResponse", safe(rr.getDefaultResponse()));

            appendRequest(doc, r, rr.getRequest());
            appendResponseSelection(doc, r, rr.getResponseSelection());
            appendResponseSet(doc, r, rr.getResponseSet());

            parent.appendChild(r);
        }
    }

    private void appendTCPRRPairs(Document doc, Element parent, LinkedList<RRPair> list) {
        if (list == null || list.isEmpty())
            return;

        for (RRPair rr : list) {
            Element r = el(doc, "RRPair");
            attr(r, "id", safe(rr.getId()));
            attr(r, "defaultResponse", safe(rr.getDefaultResponse()));

            appendTCPRequest(doc, r, rr.getRequest());
            appendResponseSelection(doc, r, rr.getResponseSelection());
            appendResponseSet(doc, r, rr.getResponseSet(), "stubResponse");

            parent.appendChild(r);
        }
    }

    private void appendRequest(Document doc, Element parent, Request req) {
        Element r = el(doc, "Request");
        if (req == null) {
            parent.appendChild(r);
            return;
        }

        // Optional contentType attribute as shown in POST sample
        if (nonEmpty(req.getContentType())) {
            attr(r, "contentType", req.getContentType());
        }

        cdata(r, "RequestData", req.getRequestData());

        if (req.getRequestParameters() != null && !req.getRequestParameters().isEmpty()) {
            Element ps = el(doc, "RequestParameters");
            for (Argument a : req.getRequestParameters()) {
                Element arg = el(doc, "arg"); // exact tag from sample
                attr(arg, "name", safe(a.getName()));
                attr(arg, "matchType", safe(a.getMatchType()));
                attr(arg, "case", safe(a.isCaseSensitive()));
                attr(arg, "echoValue", safe(a.isEchoValue()));
                attr(arg, "selected", safe(a.isSelected()));
                if (nonEmpty(a.getValue())) {
                    arg.appendChild(doc.createTextNode(a.getValue()));
                }
                ps.appendChild(arg);
            }
            r.appendChild(ps);
        }

        if (req.getHeaders() != null && !req.getHeaders().isEmpty()) {
            Element headers = el(doc, "Headers");

            for (Argument a : req.getHeaders()) {
                Element arg = el(doc, "arg");

                attr(arg, "name", safe(a.getName()));
                attr(arg, "matchType", safe(a.getMatchType()));
                attr(arg, "case", safe(a.isCaseSensitive()));
                attr(arg, "echoValue", safe(a.isEchoValue()));
                attr(arg, "selected", safe(a.isSelected()));

                if (nonEmpty(a.getValue())) {
                    arg.appendChild(doc.createTextNode(a.getValue()));
                }

                headers.appendChild(arg);
            }

            r.appendChild(headers);
        }

        if (req.getQueryParams() != null && !req.getQueryParams().isEmpty()) {
            Element qp = el(doc, "QueryParams");

            for (Argument a : req.getQueryParams()) {
                Element arg = el(doc, "arg");

                attr(arg, "name", safe(a.getName()));
                attr(arg, "matchType", safe(a.getMatchType()));
                attr(arg, "case", safe(a.isCaseSensitive()));
                attr(arg, "echoValue", safe(a.isEchoValue()));
                attr(arg, "selected", safe(a.isSelected()));

                if (nonEmpty(a.getValue())) {
                    arg.appendChild(doc.createTextNode(a.getValue()));
                }

                qp.appendChild(arg);
            }

            r.appendChild(qp);
        }

        parent.appendChild(r);
    }

    private void appendTCPRequest(Document doc, Element parent, Request req) {
        Element r = el(doc, "Request");
        if (req == null) {
            parent.appendChild(r);
            return;
        }

        // Optional contentType attribute as shown in POST sample
        if (nonEmpty(req.getContentType())) {
            attr(r, "contentType", req.getContentType());
        }


        cdata(r, "RequestData", req.getRequestData());
        cdata(r, "TagLength", req.getTagLength());
        cdata(r, "LengthSize", req.getLengthSize());

        if (req.getRequestParameters() != null && !req.getRequestParameters().isEmpty()) {
            Element ps = el(doc, "RequestParameters");
            for (Argument a : req.getRequestParameters()) {
                Element arg = el(doc, "arg"); // exact tag from sample
                attr(arg, "name", safe(a.getName()));
                attr(arg, "matchType", safe(a.getMatchType()));
                attr(arg, "case", safe(a.isCaseSensitive()));
                attr(arg, "echoValue", safe(a.isEchoValue()));
                attr(arg, "selected", safe(a.isSelected()));
                if (nonEmpty(a.getValue())) {
                    arg.appendChild(doc.createTextNode(a.getValue()));
                }
                ps.appendChild(arg);
            }
            r.appendChild(ps);
        }



        parent.appendChild(r);
    }

    private void appendResponseSelection(Document doc, Element parent, ResponseSelection rs) {
        Element rsel = el(doc, "ResponseSelection");
        if (rs != null) {
            text(rsel, "MatchStyle", rs.getMatchStyle());
            Script sc = rs.getMatchScript();
            if (sc != null) {
                Element ms = el(doc, "MatchScript");
                text(ms, "ScriptLang", sc.getScriptLanguage());
                if (nonEmpty(sc.getScript())) {
                    cdata(ms, "Script", sc.getScript());
                } else {
                    // sample sometimes has empty <vs:MatchScript/>
                    ms.appendChild(el(rsel.getOwnerDocument(), "Script"));
                }
                rsel.appendChild(ms);
            } else {
                rsel.appendChild(el(rsel.getOwnerDocument(), "MatchScript"));
            }
        } else {
            rsel.appendChild(el(rsel.getOwnerDocument(), "MatchScript"));
        }
        parent.appendChild(rsel);
    }

    private void appendResponseSet(Document doc, Element parent, LinkedList<Response> list) {
        appendResponseSet(doc, parent, list, "ResponseSet");
    }

    private void appendResponseSet(Document doc, Element parent, LinkedList<Response> list, String wrapperTag) {
        if (list == null || list.isEmpty())
            return;

        Element rs = el(doc, wrapperTag);
        for (Response r : list) {
            Element re = el(doc, "Response");
            attr(re, "name", safe(r.getName()));
            if (r.getStatusCode() > 0)
                attr(re, "statusCode", String.valueOf(r.getStatusCode()));
            attr(re, "httpMsg", safe(r.getHttpMsg()));
            attr(re, "contentType", safe(r.getContentType()));
            if (r.getResponseDelay() >= 0)
                attr(re, "responseDelay", String.valueOf(r.getResponseDelay()));

            // TCP responses carry an optional <vs:Header> before and <vs:Footer> after the body
            text(re, "Header", r.getHeader());

            // Under ResponseSet, sample uses capitalized <vs:Body>
            cdata(re, "Body", r.getBody());

            text(re, "Footer", r.getFooter());

            Script s = r.getResponseScript();
            if (s != null) {
                Element rscr = el(doc, "ResponseScript");
                text(rscr, "ScriptLang", s.getScriptLanguage());
                if (nonEmpty(s.getScript()))
                    cdata(rscr, "Script", s.getScript());
                else
                    rscr.appendChild(el(rscr.getOwnerDocument(), "Script"));
                re.appendChild(rscr);
            }

            if (r.getCustomHeaders() != null && !r.getCustomHeaders().isEmpty()) {
                Element hs = el(doc, "CustomHeaders");
                for (GenericProperty gp : r.getCustomHeaders()) {
                    Element h = el(doc, "Header");
                    text(h, "Key", gp.getKey());
                    text(h, "Value", gp.getValue());
                    hs.appendChild(h);
                }
                re.appendChild(hs);
            }

            rs.appendChild(re);
        }
        parent.appendChild(rs);
    }

    private void appendSoapService(Document doc, Element parent, VirtualServiceObject vso) {

        SOAPService ss = vso.getSoapService();
        if (ss == null)
            return;
        appendServiceDefinition(doc, parent, vso);

        Element svc = el(doc, "SOAPService");

        attr(svc, "host", safe(vso.getHost()));
        if (vso.getPort() > 0)
            attr(svc, "port", String.valueOf(vso.getPort()));
        attr(svc, "contextPath", safe(vso.getContextPath()));
        attr(svc, "autoRestart", String.valueOf(vso.isAutoStart()));
        attr(svc, "allowFallback", String.valueOf(vso.isAllowFallback()));
        attr(svc, "isSecured", String.valueOf(vso.isSecured()));

        appendExecutionMode(doc, svc, ss.getExeMode());
        appendSecuritySettings(doc, svc, ss.getSecuritySettings());
        appendDefaultError(doc, svc, ss.getDefaultErrorResponse());

        // ✅ headerValidator
        Element headerValidator = el(doc, "headerValidator");
        headerValidator.setAttribute("type", "NONE");
        svc.appendChild(headerValidator);

        // ✅ Stub Operations
        appendStubOperations(doc, svc, ss.getStubOperations());

        parent.appendChild(svc);
    }

    private void appendTCPService(Document doc, Element parent, VirtualServiceObject vso) {

        TCPService ss = vso.getTcpService();
        if (ss == null)
            return;
        appendServiceDefinition(doc, parent, vso);

        Element svc = el(doc, "TCPService");

        attr(svc, "host", safe(vso.getHost()));
        if (vso.getPort() > 0)
            attr(svc, "port", String.valueOf(vso.getPort()));

        attr(svc, "autoRestart", String.valueOf(vso.isAutoStart()));
        attr(svc, "allowFallback", String.valueOf(vso.isAllowFallback()));



        appendDefaultError(doc, svc, ss.getDefaultErrorResponse());
        appendTCPHandlers(doc, svc, ss.getTcpHandler());
        // ✅ headerValidator
        Element headerValidator = el(doc, "headerValidator");
        headerValidator.setAttribute("type", "NONE");
        svc.appendChild(headerValidator);

        // ✅ Stub Operations
        appendTcpOperations(doc, svc, ss.getTcpOperations());

        parent.appendChild(svc);
    }

    private void appendTCPHandlers(Document doc, Element svc, TCPHandler tcpHandler) {

        if (tcpHandler == null )
            return;

        Element tcpHandlers = el(doc, "TCPHandlers");

       TCPRequestHandler tcpRequestHandler = tcpHandler.getRequestHandler();

            Element req = el(doc, "RequestHandler");

        textOrEmpty(req, "Type", tcpRequestHandler.getType());
        if (tcpRequestHandler.getScript() != null) {
            textOrEmpty(req, "ScriptLang", tcpRequestHandler.getScript().getScriptLanguage());
            textOrEmpty(req, "Script", tcpRequestHandler.getScript().getScript());
        }
        textOrEmpty(req, "HeaderLength", tcpRequestHandler.getHeaderLength());
        textOrEmpty(req, "FooterLength", tcpRequestHandler.getFooterLength());
        textOrEmpty(req, "TagLength", tcpRequestHandler.getTagLength());
        textOrEmpty(req, "LengthSize", tcpRequestHandler.getLengthSize());
        Element content = el(doc, "ContentLengthPosition");

        if (tcpRequestHandler.getContentStartPosition() != null) {
            textOrEmpty(content, "Start", tcpRequestHandler.getContentStartPosition());
        }

        if (tcpRequestHandler.getContentEndPosition() != null) {
            textOrEmpty(content, "End", tcpRequestHandler.getContentEndPosition());
        }
        req.appendChild(content);
        tcpHandlers.appendChild(req);
        Element res = el(doc, "ResponseHandler");
        TCPResponseHandler responseHandler = tcpHandler.getResponseHandler();
        textOrEmpty(res, "Type", responseHandler.getType());
        if (responseHandler.getScript() != null) {
            textOrEmpty(res, "ScriptLang", responseHandler.getScript().getScriptLanguage());
            textOrEmpty(res, "Script", responseHandler.getScript().getScript());
        }

           tcpHandlers.appendChild(res);


        svc.appendChild(tcpHandlers);

    }

    private void appendStubOperations(Document doc, Element parent, LinkedList<StubOperation> ops) {

        if (ops == null || ops.isEmpty())
            return;

        Element so = el(doc, "StubOperations");

        for (StubOperation op : ops) {

            Element s = el(doc, "StubOperation");

            attr(s, "name", op.getName());
            attr(s, "bindingstubOperationName", op.getBindingstubOperationName());
            attr(s, "defaultRR", op.getDefaultRR());

            appendDataSourceSelect(doc, s, op.getDataSourceSelect());
            appendDataGenerators(doc, s, op.getDataGenerators());
            appendFilters(doc, s, op.getFilterList());
            appendRequestData(doc, s, op.getRequestList());
            appendRRPairs(doc, s, op.getRrList());

            so.appendChild(s);
        }

        parent.appendChild(so);
    }

    private void appendTcpOperations(Document doc, Element parent, LinkedList<TCPOperation> ops) {

        if (ops == null || ops.isEmpty())
            return;

        Element so = el(doc, "StubOperations");

        for (TCPOperation op : ops) {

            Element s = el(doc, "StubOperation");

            attr(s, "name", op.getName());

            attr(s, "defaultRR", op.getDefaultRR());

            appendDataSourceSelect(doc, s, op.getDataSourceSelect());
            appendDataGenerators(doc, s, op.getDataGenerators());
            appendFilters(doc, s, op.getFilterList());
            appendRequestData(doc, s, op.getRequestList());
            appendTCPRRPairs(doc, s, op.getRrList());

            so.appendChild(s);
        }

        parent.appendChild(so);
    }

    private void appendServiceDefinition(Document doc, Element parent, VirtualServiceObject vso) {

        if (vso.getWsdlMetaData() == null)
            return;

        WSDLMetadata meta = vso.getWsdlMetaData();

        Element sd = el(doc, "ServiceDefinition");

        Element wsdlMeta = el(doc, "WSDLMetadata");
        attr(wsdlMeta, "type", safe(meta.getType()));
        attr(wsdlMeta, "rootPart", safe(meta.getRootPart()));

        Element part = el(doc, "WSDLPart");
        text(part, "WSDLURL", meta.getUrl());
        text(part, "WSDLText", meta.getWsdlText());

        wsdlMeta.appendChild(part);
        sd.appendChild(wsdlMeta);
        parent.appendChild(sd);
    }

    private void appendDataSourceSelect(Document doc, Element parent, DataSourceSelect dss) {
        if (dss == null)
            return;

        Element ds = el(doc, "DataSourceSelect");

        // =====================
        // ✅ DATABASE SECTION
        // =====================

        if (dss.getDatabase() == null || dss.getDatabase().isEmpty()) {

            // ✅ Write empty <Database/>
            ds.appendChild(el(doc, "Database"));

        } else {

            for (DataBase db : dss.getDatabase()) {

                if (db == null)
                    continue;

                Element d = el(doc, "Database");

                textOrEmpty(d, "ConnectionName", db.getConnectionName());

                textOrEmpty(d, "DataSourceName", db.getDsName());

                cdata(d, "Query", db.getQuery());

                // ✅ ALWAYS create ResultProperties
                /*
                 * Element rp = el(doc, "ResultProperties");
                 *
                 * if (db.getResultProperties() != null) {
                 *
                 * for (GenericProperty gp : db.getResultProperties()) {
                 *
                 * Element r = el(doc, "ResultProperty");
                 *
                 * textOrEmpty(r, "Name", gp.getKey());
                 *
                 * // ✅ IMPORTANT: even if empty → create empty tag
                 * if (gp.getValue() != null && !gp.getValue().isBlank()) {
                 * text(r, "ColumnName", gp.getValue());
                 * } else {
                 * r.appendChild(el(doc, "ColumnName")); // <ColumnName/>
                 * }
                 *
                 * rp.appendChild(r);
                 * }
                 * }
                 *
                 * d.appendChild(rp);
                 */
                ds.appendChild(d);
            }
        }

        // =====================
        // ✅ FILE SECTION
        // =====================

        if (dss.getFile() == null || dss.getFile().isEmpty()) {

            Element f = el(doc, "File");

            ds.appendChild(f);

        } else {

            for (DataFile df : dss.getFile()) {

                Element f = el(doc, "File");

                textOrEmpty(f, "DataSourceName", df.getDsName());
                textOrEmpty(f, "FileLocation", df.getFileLocation());
                textOrEmpty(f, "FetchType", df.getFetchType());
                textOrEmpty(f, "Sheet", df.getSheet());
                textOrEmpty(f, "MappingType", df.getMappingType());
                if (df.getRandomCount() != null) {
                    text(f, "RandomCount", String.valueOf(df.getRandomCount()));
                }

                // DataFile.request is only set when the Request object could be found;
                // fall back to the stored name so a save never erases RequestName.
                String requestName = (df.getRequest() != null && df.getRequest().getName() != null)
                        ? df.getRequest().getName()
                        : (df.getRequestName() == null ? "" : df.getRequestName());

                textOrEmpty(f, "RequestName", requestName);

                Element rms = el(doc, "RequestColumnMappings");

                if (df.getMappings() != null && !df.getMappings().isEmpty()) {

                    for (RequestColumnMapping m : df.getMappings()) {

                        Element mm = el(doc, "RequestColumnMapping");

                        textOrEmpty(mm, "RequestMapping", m.getRequestParameter());
                        textOrEmpty(mm, "ComparisonType", m.getComparisonType());

                        if (m.getColumnName() != null && !m.getColumnName().isBlank())
                            text(mm, "ColumnName", m.getColumnName());
                        else
                            mm.appendChild(el(doc, "ColumnName"));

                        rms.appendChild(mm);
                    }
                }

                // ✅ ensure exists even if empty
                if (rms.getChildNodes().getLength() == 0) {
                    f.appendChild(rms);
                } else {
                    f.appendChild(rms);
                }

                ds.appendChild(f);

            }
        }

        parent.appendChild(ds);
    }

    private void appendDataGenerators(Document doc, Element parent, LinkedList<DataGenerator> list) {
        if (list == null)
            return;

        Element gs = null;

        for (DataGenerator dg : list) {
            if (isEmpty(dg))
                continue;

            if (gs == null) {
                gs = el(doc, "DataGenerators");
            }

            Element g = el(doc, "DataGenerator");
            text(g, "Type", dg.getType());

            if (dg.getStartNumber() != null)
                text(g, "StartNumber", String.valueOf(dg.getStartNumber()));
            if (dg.getEndNumber() != null)
                text(g, "EndNumber", String.valueOf(dg.getEndNumber()));
            if (dg.getIncrement() != null)
                text(g, "Increment", String.valueOf(dg.getIncrement()));
            if (dg.getLength() != null)
                text(g, "Length", String.valueOf(dg.getLength()));

            text(g, "Variable", dg.getVariable());
            text(g, "prefix", dg.getPrefix());

            text(g, "IsAlphabet", String.valueOf(dg.isAlphabets()));
            text(g, "IsNumeric", String.valueOf(dg.isNumbers()));
            text(g, "IsSpecial", String.valueOf(dg.isSpecialChar()));

            gs.appendChild(g);
        }

        if (gs != null) {
            parent.appendChild(gs);
        }
    }

    private boolean isEmpty(DataGenerator dg) {
        return dg == null
                || isBlank(dg.getType())
                        && dg.getStartNumber() == null
                        && dg.getEndNumber() == null
                        && dg.getIncrement() == null
                        && dg.getLength() == null
                        && isBlank(dg.getVariable())
                        && isBlank(dg.getPrefix());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isEmpty(GenericProperty gp) {
        return gp == null
                || (isBlank(gp.getKey()) && isBlank(gp.getValue()));
    }

    private void appendCustomScripts(Document doc, Element parent, VirtualServiceObject vso) {
        LinkedHashMap<String, Script> scripts = vso.getCustomScripts();
        if (scripts == null || scripts.isEmpty())
            return;

        Element cs = el(doc, "CustomScripts");
        for (Map.Entry<String, Script> e : scripts.entrySet()) {
            Script sc = e.getValue();
            Element s = el(doc, "CustomScript");
            text(s, "ScriptLang", sc.getScriptLanguage());
            text(s, "ExecutionType", sc.getScriptType());
            cdata(s, "Script", sc.getScript());
            cs.appendChild(s);
        }
        parent.appendChild(cs);
    }

    private void appendConfig(Document doc, Element parent, VirtualServiceObject vso) {
        Element cfg = el(doc, "Config");

        if (vso.getMaxThreads() > 0 || vso.getCoreThreads() > 0) {
            Element tp = el(doc, "ThreadPool");
            if (vso.getMaxThreads() > 0)
                attr(tp, "maxThreads", String.valueOf(vso.getMaxThreads()));
            if (vso.getCoreThreads() > 0)
                attr(tp, "coreThreads", String.valueOf(vso.getCoreThreads()));
            cfg.appendChild(tp);
        }

        text(cfg, "ResponseDelay", String.valueOf(Math.max(0, vso.getResponseDelay())));

        parent.appendChild(cfg);
    }

    private Element el(Document doc, String tag) {
        return doc.createElementNS(VS_NS, VS_PREFIX + ":" + tag);
    }

    private void text(Element parent, String tag, String value) {
        if (value == null)
            return;
        Element e = el(parent.getOwnerDocument(), tag);
        e.appendChild(parent.getOwnerDocument().createTextNode(value));
        parent.appendChild(e);
    }

    private void textOrEmpty(Element parent, String tag, String value) {
        if (value != null)
            if (!value.isBlank()) {

                text(parent, tag, value);
            } else {
                text(parent, tag, "");
            }
    }

    private void cdata(Element parent, String tag, String value) {
        if (value == null)
            return;
        Element e = el(parent.getOwnerDocument(), tag);
        CDATASection cdata = parent.getOwnerDocument().createCDATASection(value);
        e.appendChild(cdata);
        parent.appendChild(e);
    }

    private void attr(Element e, String name, String value) {
        if (value != null && !value.isBlank()) {
            e.setAttribute(name, value);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;

    }

    private boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void writeDocument(Document doc, String path) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.METHOD, "xml");
        t.transform(new DOMSource(doc), new StreamResult(new File(path)));
    }
}

