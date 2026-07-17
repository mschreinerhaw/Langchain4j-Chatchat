package com.chatchat.runtime.mcp.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "chatchat.mcp")
public class McpCapabilitiesProperties {
    private Map<String, Capability> capabilities = new LinkedHashMap<>();

    public Map<String, Capability> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Capability> capabilities) { this.capabilities = capabilities; }

    public Tool tool(String capabilityCode, String toolName) {
        Capability capability = capabilities.get(capabilityCode);
        return capability == null ? null : capability.getTools().get(toolName);
    }

    public static class Capability {
        private Boolean enabled;
        private Map<String, Tool> tools = new LinkedHashMap<>();
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Map<String, Tool> getTools() { return tools; }
        public void setTools(Map<String, Tool> tools) { this.tools = tools; }
    }

    public static class Tool {
        private Boolean enabled;
        private Boolean agentCallable;
        private Duration timeout;
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Boolean getAgentCallable() { return agentCallable; }
        public void setAgentCallable(Boolean agentCallable) { this.agentCallable = agentCallable; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }
}
