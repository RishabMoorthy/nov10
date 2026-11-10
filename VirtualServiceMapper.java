package com.stubio.util;

import com.stubio.parsers.*;


import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.LinkedHashMap;

public class VirtualServiceMapper {

private final String file;

public final VirtualServiceObject vso;

public VirtualServiceMapper(String file) {

this.file = file;
this.vso = generateVSO();
}

public VirtualServiceObject getVso() {
return vso;
}

private VirtualServiceObject generateVSO() {

try {

DocumentBuilderFactory factory =
DocumentBuilderFactory.newInstance();

factory.setNamespaceAware(true);

DocumentBuilder builder =
factory.newDocumentBuilder();

Document document =
builder.parse(file);

document.getDocumentElement().normalize();

Element root =
document.getDocumentElement();

VirtualServiceObject vs =
new VirtualServiceObject();

populateRootAttributes(root, vs);

parseRootChildren(root, vs);

return vs;

} catch (Exception ex) {

throw new RuntimeException(
"Failed to parse XML : " + file,
ex);
}
}

private void populateRootAttributes(
Element root,
VirtualServiceObject vs) {

vs.setXmlPath(file);

vs.setVsName(
root.getAttribute("name"));

vs.setEnvironment(
root.getAttribute("environment"));

String id =
root.getAttribute("projectId");
if (id.isBlank()) {
id = root.getAttribute("Id");
}

if (!id.isBlank()) {
vs.setId(id);
}
}

private void parseRootChildren(
Element root,
VirtualServiceObject vs) {

for (Element child :
XmlUtils.childElements(root)) {

switch (XmlUtils.local(child)) {

case "Properties" ->
vs.setPropertyList(
PropertiesParser.parse(child));

case "DataSources" ->
vs.setDataSourceList(
DataSourceParser.parse(child));

case "ExecutionMode" ->
vs.setExeMode(
ExecutionModeParser.parse(child));

case "DefaultError" ->
vs.setDefaultErrorResponse(
DefaultErrorParser.parse(child));

case "TCPHandler" ->
vs.setTcpHandler(
TCPHandlerParser.parse(child));

case "ServiceDefinition" ->
vs.setWsdlMetaData(
WSDLMetadataParser.parse(child));

case "RestService" ->
parseRestService(child, vs);

case "SOAPService" ->
parseSoapService(child, vs);

case "TCPService" ->
parseTcpService(child, vs);

case "CustomScripts" ->
vs.setCustomScripts(
parseCustomScripts(child));

case "Config" ->
ConfigParser.parse(child, vs);

default -> {
// ignore
}
}
}
}

private void parseRestService(
Element node,
VirtualServiceObject vs) {

vs.setVsType("rest");

mapCommonServiceAttributes(node, vs);

RestService restService =
RestServiceParser.parse(node, vs);

vs.setRestService(restService);
}

private void parseSoapService(
Element node,
VirtualServiceObject vs) {

vs.setVsType("soap");

mapCommonServiceAttributes(node, vs);

SOAPService soapService =
SoapServiceParser.parse(node, vs);

vs.setSoapService(soapService);
}

private void parseTcpService(
Element node,
VirtualServiceObject vs) {

vs.setVsType("tcp");

mapCommonServiceAttributes(node, vs);


TCPService tcpService =
TCPServiceParser.parse(node, vs);

vs.setTcpService(tcpService);
}

private void mapCommonServiceAttributes(
Element serviceNode,
VirtualServiceObject vs) {

String port =
serviceNode.getAttribute("port");

if (!port.isBlank()) {

try {
vs.setPort(
Integer.parseInt(port));
}
catch (NumberFormatException ignored) {
}
}

vs.setHost(
serviceNode.getAttribute("host"));

vs.setContextPath(
serviceNode.getAttribute("contextPath"));

vs.setAutoStart(
Boolean.parseBoolean(
serviceNode.getAttribute(
"autoRestart")));

vs.setAllowFallback(
Boolean.parseBoolean(
serviceNode.getAttribute(
"allowFallback")));

vs.setSecured(
Boolean.parseBoolean(
serviceNode.getAttribute(
"isSecured")));
}

private LinkedHashMap<String, Script> parseCustomScripts(
Element customScriptsNode) {

LinkedHashMap<String, Script> scripts =
new LinkedHashMap<>();

for (Element scriptNode :
XmlUtils.childElements(customScriptsNode)) {

if (!"CustomScript".equals(
XmlUtils.local(scriptNode))) {
continue;
}

ScriptParser scriptParser = new ScriptParser();
Script script = scriptParser.parse(scriptNode);

if (script.getScriptType() != null
&& !script.getScriptType().isBlank()) {

scripts.put(
script.getScriptType(),
script);
}
}
return scripts;
}
}
