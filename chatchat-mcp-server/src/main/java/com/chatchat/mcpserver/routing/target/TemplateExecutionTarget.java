package com.chatchat.mcpserver.routing.target;

/** In-process authority created after template authorization. JSON maps cannot impersonate this value. */
public record TemplateExecutionTarget(String assetType, String resourceId) {
    public static final String KEY = "__templateExecutionTarget";
}
