package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterpretationPlanRecord(
    String tenantId,
    String taskId,
    String planId,
    Integer version,
    String planJson,
    String dagJson,
    Map<String, Object> dag,
    String status,
    Long createdAt,
    Long updatedAt
) {
}
