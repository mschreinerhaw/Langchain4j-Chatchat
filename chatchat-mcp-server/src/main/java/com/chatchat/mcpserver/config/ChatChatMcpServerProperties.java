package com.chatchat.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "chatchat.mcp.server")
public class ChatChatMcpServerProperties {

    private String name = "chatchat-langchain4j-mcp-server";
    private String version = "1.0.0-SNAPSHOT";
    private String endpoint = "/mcp";
    private boolean exposeAgentCompatibleOnly = true;
    private Set<String> excludedToolNames = new LinkedHashSet<>(Set.of("web_search"));
    private String instructions = "ChatChat standalone MCP server exposing LangChain4j-compatible tools.";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isExposeAgentCompatibleOnly() {
        return exposeAgentCompatibleOnly;
    }

    public void setExposeAgentCompatibleOnly(boolean exposeAgentCompatibleOnly) {
        this.exposeAgentCompatibleOnly = exposeAgentCompatibleOnly;
    }

    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    public void setExcludedToolNames(Set<String> excludedToolNames) {
        this.excludedToolNames = excludedToolNames == null ? new LinkedHashSet<>() : excludedToolNames;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
