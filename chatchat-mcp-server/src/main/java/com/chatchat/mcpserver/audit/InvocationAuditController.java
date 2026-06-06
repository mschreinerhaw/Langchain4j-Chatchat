package com.chatchat.mcpserver.audit;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
        return ApiResponse.success(auditService.listRecent().stream().map(this::toView).toList());
    }

    private AuditLogView toView(InvocationAuditLog log) {
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
            log.getRequestSummary(),
            log.getResponseSummary(),
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
