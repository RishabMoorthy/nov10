package com.stubio.parsers;

import com.stubio.util.Request;
import org.w3c.dom.Element;

import java.util.LinkedHashMap;

public final class RequestDataParser {

    private RequestDataParser() {
    }

    public static LinkedHashMap<String, Request> parse(
            Element requestDataNode) {

        LinkedHashMap<String, Request> requests =
                new LinkedHashMap<>();

        for (Element requestNode :
                XmlUtils.childElements(requestDataNode)) {

            if (!"Request".equals(
                    XmlUtils.local(requestNode))) {
                continue;
            }

            Request request =
                    new Request();

            String requestName = null;

            for (Element child :
                    XmlUtils.childElements(requestNode)) {

                switch (XmlUtils.local(child)) {

                    case "Name" -> {
                        requestName =
                                XmlUtils.text(child);
                        request.setName(requestName);
                    }

                    case "ContentType" ->
                            request.setContentType(
                                    XmlUtils.text(child));

                    case "Body" ->
                            request.setRequestData(
                                    child.getTextContent());

                    case "TagLength" ->
                            request.setTagLength(
                                    XmlUtils.text(child));

                    case "LengthSize" ->
                            request.setLengthSize(
                                    XmlUtils.text(child));
                }
            }

            if (requestName != null) {
                requests.put(requestName, request);
            }
        }

        return requests;
    }
}

