package com.chatchat.mcpserver.audit;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/audit-logs")
public class InvocationAuditController {

    private final InvocationAuditService auditService;

    @GetMapping
    public ApiResponse<List<AuditLogView>> listRecent() {
        return ApiResponse.success(auditService.listRecent().stream().map(log -> toView(log, false)).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AuditLogView> detail(@PathVariable("id") String id) {
        return ApiResponse.success(
            toView(auditService.findById(id).orElseThrow(() -> new IllegalArgumentException("Audit log not found: " + id)), true)
        );
    }

    private AuditLogView toView(InvocationAuditLog log, boolean includeDetails) {
        return new AuditLogView(
            log.getId(),
            log.getTargetType(),
            log.getTargetId(),
            log.getTargetName(),
            log.getCaller(),
            log.isSuccess(),
            log.getStatusCode(),
            log.getDurationMs(),
            log.getErrorMessage(),
            includeDetails ? log.getRequestSummary() : null,
            includeDetails ? log.getResponseSummary() : null,
            log.getCreatedAt() == null ? null : log.getCreatedAt().toEpochMilli()
        );
    }

    public record AuditLogView(
        String id,
        String targetType,
        String targetId,
        String targetName,
        String caller,
        boolean success,
        Integer statusCode,
        Long durationMs,
        String errorMessage,
        String requestSummary,
        String responseSummary,
        Long createdAt
    ) {
    }
}
