package com.chatchat.mcpserver.audit;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.audit.InvocationAuditService.AuditLogPage;
import com.chatchat.mcpserver.audit.InvocationAuditService.AuditLogSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/audit-logs")
public class InvocationAuditController {

    private final InvocationAuditService auditService;

    @GetMapping
    public ApiResponse<AuditLogPageView> search(
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "pageSize", required = false) Integer pageSize,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "targetType", required = false) String targetType,
        @RequestParam(value = "targetId", required = false) String targetId,
        @RequestParam(value = "toolName", required = false) String toolName,
        @RequestParam(value = "caller", required = false) String caller,
        @RequestParam(value = "success", required = false) Boolean success,
        @RequestParam(value = "statusCode", required = false) Integer statusCode,
        @RequestParam(value = "from", required = false) Long from,
        @RequestParam(value = "to", required = false) Long to
    ) {
        AuditLogPage result = auditService.search(new AuditLogSearchQuery(
            page,
            pageSize,
            keyword,
            targetType,
            targetId,
            toolName,
            caller,
            success,
            statusCode,
            from,
            to
        ));
        return ApiResponse.success(toPageView(result));
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
            log.getToolName(),
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

    private AuditLogPageView toPageView(AuditLogPage page) {
        int totalPages = Math.max(1, (int) Math.ceil(page.filteredCount() / (double) Math.max(1, page.pageSize())));
        return new AuditLogPageView(
            page.items().stream().map(log -> toView(log, false)).toList(),
            page.page(),
            page.pageSize(),
            page.totalCount(),
            page.filteredCount(),
            totalPages,
            page.page() > 1,
            page.page() < totalPages
        );
    }

    public record AuditLogView(
        String id,
        String targetType,
        String targetId,
        String targetName,
        String toolName,
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

    public record AuditLogPageView(
        List<AuditLogView> items,
        int page,
        int pageSize,
        long totalCount,
        long filteredCount,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
    ) {
    }
}
