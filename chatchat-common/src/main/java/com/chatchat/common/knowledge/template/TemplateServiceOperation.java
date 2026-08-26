package com.chatchat.common.knowledge.template;

/** Capabilities exposed by a governed template service, independent of its transport. */
public enum TemplateServiceOperation {
    SEARCH("template.service/search"),
    EXECUTE("template.service/execute");

    private final String operationCode;

    TemplateServiceOperation(String operationCode) {
        this.operationCode = operationCode;
    }

    public String operationCode() {
        return operationCode;
    }
}
