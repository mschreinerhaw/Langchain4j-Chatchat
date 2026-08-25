package com.chatchat.common.bridge.api;

/** Operations available on the transport-neutral MCP to API communication boundary. */
public enum McpApiOperation {
    TEMPLATE_SEARCH("api.service/query"),
    TEMPLATE_EXECUTE("api.service/execute");

    private final String operationCode;

    McpApiOperation(String operationCode) {
        this.operationCode = operationCode;
    }

    public String operationCode() {
        return operationCode;
    }
}
