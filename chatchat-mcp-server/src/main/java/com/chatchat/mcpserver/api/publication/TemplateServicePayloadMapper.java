package com.chatchat.mcpserver.api.publication;

import com.chatchat.common.knowledge.template.TemplateServiceResult;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP transport projection for the transport-neutral template service result. */
final class TemplateServicePayloadMapper {
    static final String WIRE_CALL_SCHEMA_VERSION = "mcp_api_call.v1";
    static final String WIRE_RESULT_SCHEMA_VERSION = "mcp_api_result.v1";

    private TemplateServicePayloadMapper() {
    }

    static Map<String, Object> payload(TemplateServiceResult result) {
        Map<String, Object> payload = new LinkedHashMap<>(result.data());
        payload.put("communicationSchemaVersion", WIRE_RESULT_SCHEMA_VERSION);
        payload.put("communicationRequestId", result.requestId());
        payload.put("communicationOperation", switch (result.operation()) {
            case SEARCH -> "api.service/query";
            case EXECUTE -> "api.service/execute";
        });
        payload.put("communicationStatus", result.status().name());
        payload.put("resultKind", "RAW_RECORDS");
        payload.put("resultSchemaRef", "tool_execution_result.v1");
        payload.put("resultEntityKind", "execution_result");
        String templateId = result.metadata().get("templateId") == null ? null
            : String.valueOf(result.metadata().get("templateId"));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("sourceRef", "mcp://api_template_execute"
            + (templateId == null || templateId.isBlank() ? "" : "/" + templateId));
        provenance.put("dataVersion", "tool_execution_result.v1");
        provenance.put("asOf", Instant.ofEpochMilli(result.completedAt()).toString());
        if (templateId != null && !templateId.isBlank()) {
            provenance.put("filterSummary", Map.of("templateId", templateId));
        }
        payload.put("provenance", Map.copyOf(provenance));
        payload.put("events", result.events());
        payload.put("retryable", result.retryable());
        return Collections.unmodifiableMap(payload);
    }
}
