package com.chatchat.mcpserver.templatepublication.admin;
import com.fasterxml.jackson.databind.JsonNode;

/** Add another controlled tool kind by implementing this port; release policy and UI stay shared. */
public interface ToolReleaseExecutor {
    String kind();
    String toolName(JsonNode definition);
    String stableIdentity(JsonNode definition);
    void validate(JsonNode definition);
    JsonNode test(JsonNode definition,JsonNode input);
    String activate(JsonNode definition,String executionReference);
    void offline(String executionReference);
    default boolean unmanagedExistingDefinition(JsonNode definition) { return false; }
}
