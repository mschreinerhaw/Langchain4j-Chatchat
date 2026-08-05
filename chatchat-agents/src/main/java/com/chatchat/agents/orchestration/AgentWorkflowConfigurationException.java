package com.chatchat.agents.orchestration;

/**
 * Signals an invalid workflow dependency contract before agent execution starts.
 */
public final class AgentWorkflowConfigurationException extends IllegalArgumentException {

    private final String code;

    public AgentWorkflowConfigurationException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
