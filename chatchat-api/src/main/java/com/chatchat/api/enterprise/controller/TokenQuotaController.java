package com.chatchat.api.enterprise.controller;

import com.chatchat.api.enterprise.service.TokenQuotaLedger;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/permission-center/token-pools")
@RequiredArgsConstructor
class TokenQuotaController {
    private final TokenQuotaLedger ledger;
    private final WorkspaceService workspaces;
    private final SysUserRepository users;
    private final SysRoleRepository roles;
    private final SysAuditLogRepository audits;

    record Change(boolean unlimited, Long limit, boolean reset) {}
    record AuditEntry(String id, String action, String actor, String detail, Instant createdAt) {
        static AuditEntry from(SysAuditLog log) {
            return new AuditEntry(log.getId(), log.getActionName(), log.getActorName(), log.getDetail(), log.getCreatedAt());
        }
    }
    record History(List<AuditEntry> entries, boolean hasNext, Instant lastResetAt) {}

    @GetMapping("/{type}/{principal}")
    public ApiResponse<TokenQuotaLedger.Pool> get(HttpServletRequest request, @PathVariable("type") TokenQuotaLedger.PoolType type,
                                                  @PathVariable("principal") String principal) {
        var admin = manager(request);
        requirePoolOwner(admin.tenantId(), type, principal);
        return ApiResponse.success(ledger.pool(admin.tenantId(), type, principal));
    }

    @GetMapping("/{type}/{principal}/changes")
    public ApiResponse<History> history(HttpServletRequest request, @PathVariable("type") TokenQuotaLedger.PoolType type,
                                        @PathVariable("principal") String principal, @RequestParam(name = "page", defaultValue = "1") int page) {
        var admin = manager(request);
        requirePoolOwner(admin.tenantId(), type, principal);
        Specification<SysAuditLog> scope = (root, query, builder) -> builder.and(
                builder.equal(root.get("tenantId"), admin.tenantId()),
                builder.equal(root.get("moduleName"), "token-quota"),
                builder.equal(root.get("resourceType"), type.name()),
                builder.equal(root.get("resourceId"), principal));
        var order = Sort.by("createdAt", "id").descending();
        var rows = audits.findAll(scope, PageRequest.of(Math.max(1, Math.min(page, 10000)) - 1, 10, order));
        var lastReset = audits.findAll(scope.and((root, query, builder) -> builder.equal(root.get("actionName"), "reset")),
                PageRequest.of(0, 1, order)).stream().findFirst().map(SysAuditLog::getCreatedAt).orElse(null);
        return ApiResponse.success(new History(rows.map(AuditEntry::from).getContent(), rows.hasNext(), lastReset));
    }

    @PutMapping("/{type}/{principal}")
    @Transactional
    public ApiResponse<TokenQuotaLedger.Pool> update(HttpServletRequest request, @PathVariable("type") TokenQuotaLedger.PoolType type,
                                                     @PathVariable("principal") String principal, @RequestBody Change change) {
        var admin = manager(request);
        if (!change.unlimited() && (change.limit() == null || change.limit() < 0) || change.unlimited() && change.limit() != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不限额或设置有效的 Token 上限");
        final TokenQuotaLedger.Pool updated;
        try {
            updated = ledger.configure(admin.tenantId(), type, principal, change.limit(), change.reset());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "额度对象不存在或不可访问");
        }
        var audit = new SysAuditLog();
        audit.setTenantId(admin.tenantId());
        audit.setActorId(admin.id());
        audit.setActorName(admin.username());
        audit.setModuleName("token-quota");
        audit.setActionName(change.reset() ? "reset" : "configure");
        audit.setResourceType(type.name());
        audit.setResourceId(principal);
        audit.setResult("success");
        audit.setDetail("limit=" + updated.limit() + ",period=" + updated.period());
        audits.saveAndFlush(audit);
        return ApiResponse.success(updated);
    }

    private void requirePoolOwner(String tenant, TokenQuotaLedger.PoolType type, String principal) {
        boolean exists = type == TokenQuotaLedger.PoolType.USER
            ? users.findById(principal).filter(item -> tenant.equals(item.getTenantId())).isPresent()
            : roles.findById(principal).filter(item -> tenant.equals(item.getTenantId())).isPresent();
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "额度对象不存在或不可访问");
    }

    private UserView manager(HttpServletRequest request) {
        workspaces.requireAvailable(request, "permissions");
        if (request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW) instanceof UserView user
                && user.tenantId() != null && user.permissionCodes().contains(ReportExportController.MANAGE_PERMISSION))
            return user;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要权限管理授权");
    }
}
