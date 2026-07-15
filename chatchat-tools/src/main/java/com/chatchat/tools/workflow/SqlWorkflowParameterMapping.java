package com.chatchat.tools.workflow;

/** Describes where one named SQL parameter is resolved from. */
public record SqlWorkflowParameterMapping(
    String parameter,
    String sourceType,
    String sourceKey,
    String sourceNode,
    String sourceExpression,
    Object defaultValue,
    boolean required
) {
}
