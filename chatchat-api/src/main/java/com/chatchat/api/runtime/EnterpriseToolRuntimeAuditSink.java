package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.ToolRuntimeAuditRecord;
import com.chatchat.agents.runtime.ToolRuntimeAuditSink;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EnterpriseToolRuntimeAuditSink implements ToolRuntimeAuditSink {

    private final SysAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void record(ToolRuntimeAuditRecord record) {
        if (record == null || record.request() == null) {
            return;
        }
        SysAuditLog log = new SysAuditLog();
        log.setTenantId(record.request().getTenantId());
        log.setActorId(record.request().getUserId());
        log.setActorName(record.request().getUserId());
        log.setModuleName("tool_runtime");
        log.setActionName(record.outcome());
        log.setResourceType("tool");
        log.setResourceId(record.request().getToolName());
        log.setResult(record.outcome() == null || record.outcome().isBlank() ? "success" : record.outcome());
        log.setDetail(buildDetail(record));
        auditLogRepository.save(log);
    }

    private String buildDetail(ToolRuntimeAuditRecord record) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolName", record.request().getToolName());
        detail.put("tenantId", record.request().getTenantId());
        detail.put("userId", record.request().getUserId());
        detail.put("mode", record.request().getRuntimeMode());
        detail.put("requestId", record.request().getRequestId());
        detail.put("conversationId", record.request().getConversationId());
        detail.put("serviceId", record.metadata() == null || record.metadata().getMetadata() == null
            ? null
            : record.metadata().getMetadata().get("serviceId"));
        detail.put("durationMs", record.durationMs());
        detail.put("outcome", record.outcome());
        detail.put("errorCode", record.errorCode());
        detail.put("errorMessage", record.output() == null ? null : record.output().getErrorMessage());
        detail.put("parameters", record.request().getToolInput() == null ? Map.of() : record.request().getToolInput().getParameters());
        detail.put("runtime", record.runtimeMetadata());
        try {
            String json = objectMapper.writeValueAsString(detail);
            return json.length() <= 4000 ? json : json.substring(0, 4000);
        } catch (JsonProcessingException ex) {
            return "tool=" + record.request().getToolName() + ", outcome=" + record.outcome();
        }
    }
}
