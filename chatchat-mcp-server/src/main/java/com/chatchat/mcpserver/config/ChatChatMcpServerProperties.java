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

    /**
     * Returns the name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name.
     *
     * @param name the name value
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the version.
     *
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the version.
     *
     * @param version the version value
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the endpoint.
     *
     * @return the endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the endpoint.
     *
     * @param endpoint the endpoint value
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Returns whether is expose agent compatible only.
     *
     * @return whether the condition is satisfied
     */
    public boolean isExposeAgentCompatibleOnly() {
        return exposeAgentCompatibleOnly;
    }

    /**
     * Sets the expose agent compatible only.
     *
     * @param exposeAgentCompatibleOnly the expose agent compatible only value
     */
    public void setExposeAgentCompatibleOnly(boolean exposeAgentCompatibleOnly) {
        this.exposeAgentCompatibleOnly = exposeAgentCompatibleOnly;
    }

    /**
     * Returns the excluded tool names.
     *
     * @return the excluded tool names
     */
    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    /**
     * Sets the excluded tool names.
     *
     * @param excludedToolNames the excluded tool names value
     */
    public void setExcludedToolNames(Set<String> excludedToolNames) {
        this.excludedToolNames = excludedToolNames == null ? new LinkedHashSet<>() : excludedToolNames;
    }

    /**
     * Returns the instructions.
     *
     * @return the instructions
     */
    public String getInstructions() {
        return instructions;
    }

    /**
     * Sets the instructions.
     *
     * @param instructions the instructions value
     */
    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
