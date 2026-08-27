package com.chatchat.mcpserver.routing.target;

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
