package com.stubio.parsers;

import com.stubio.util.RRPair;
import com.stubio.util.StubOperation;
import com.stubio.util.TCPOperation;
import com.stubio.util.VirtualServiceObject;
import org.w3c.dom.Element;

import java.util.LinkedList;

public final class TCPOperationParser {

    private TCPOperationParser() {
    }

    public static LinkedList<TCPOperation> parse(
            Element operationsNode,
            VirtualServiceObject vso) {

        LinkedList<TCPOperation> operations =
                new LinkedList<>();

        for (Element opNode :
                XmlUtils.childElements(operationsNode)) {

            if (!"StubOperation".equals(
                    XmlUtils.local(opNode))) {
                continue;
            }

            TCPOperation operation =
                    new TCPOperation();

            operation.setName(
                    opNode.getAttribute("name"));

            operation.setDefaultRR(
                    opNode.getAttribute(
                            "defaultRR"));

            LinkedList<RRPair> rrPairs =
                    new LinkedList<>();

            for (Element child :
                    XmlUtils.childElements(opNode)) {

                switch (XmlUtils.local(child)) {

                    case "RequestData" ->
                            operation.setRequestList(
                                    RequestDataParser.parse(
                                            child));

                    case "Filters" ->
                            operation.setFilterList(
                                    FilterParser.parse(
                                            child));

                    case "DataGenerators" ->
                            operation.setDataGenerators(
                                    DataGeneratorParser.parse(
                                            child));

                    case "DataSourceSelect" ->
                            operation.setDataSourceSelect(
                                    DataSourceSelectParser.parse(
                                            child,
                                            operation,
                                            vso));

                    case "RRPair" ->
                            rrPairs.add(
                                    RRPairParser.parse(
                                            child));
                }
            }

            DataSourceSelectParser.attachRequests(
                    operation.getDataSourceSelect(),
                    operation.getRequestList());

            operation.setRrList(rrPairs);

            operations.add(operation);
        }

        return operations;
    }
}
