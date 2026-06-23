package com.chatchat.mcpserver.routing;

import java.util.List;

public record AssetExecutionTargetBinding(
    String targetKey,
    String name,
    String description,
    String environment,
    String selectorType,
    String selectorValue,
    List<String> labels,
    Integer priority,
    Boolean enabled
) {
}
