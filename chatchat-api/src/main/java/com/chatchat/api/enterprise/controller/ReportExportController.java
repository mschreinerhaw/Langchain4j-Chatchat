package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ReportExportController {
    public static final String MANAGE_PERMISSION = "system:permission-center:manage";
    private static final String MODULE = "report-export";
    private final WorkspaceService workspaces;
    private final SysRoleRepository roles;
    private final SysAuditLogRepository audits;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReportExportSource sources;
    private final ReportPrintClient printer;
    private final EnterpriseAdminService identities;
    private final Semaphore renderSlots = new Semaphore(2, true);

    public record Policy(boolean watermarkEnabled, String watermark, Set<String> roleIds, long version) {}
    private record Role(String id, String name, boolean enabled) {}

    @PostConstruct
    void initialize() {
        jdbc.execute("""
            create table if not exists report_export_policy (
              tenant_id varchar(64) primary key, watermark_enabled boolean not null,
              watermark varchar(120) not null, role_ids text not null, version bigint not null
            )
            """);
    }

    @GetMapping("/report-exports/capability")
    public ApiResponse<?> capability(HttpServletRequest request) {
        var user = identity(request);
        return ApiResponse.success(Map.of("allowed", allowed(user, policy(user.tenantId()))));
    }

    @GetMapping("/permission-center/report-exports")
    public ApiResponse<?> settings(HttpServletRequest request) {
        var user = manager(request);
        return ApiResponse.success(Map.of("policy", policy(user.tenantId()), "roles",
            roles.findByTenantIdOrderByRoleNameAsc(user.tenantId()).stream()
                .map(role -> new Role(role.getId(), role.getRoleName(), "enabled".equals(role.getStatus()))).toList()));
    }

    @PutMapping("/permission-center/report-exports")
    @Transactional
    public ApiResponse<Policy> save(HttpServletRequest request, @RequestBody Policy input) throws IOException {
        var user = manager(request);
        String text = input.watermark() == null ? "" : input.watermark().strip();
        if (text.length() > 120 || text.codePoints().anyMatch(Character::isISOControl)
                || input.watermarkEnabled() && (text.isBlank() || !ReportPdf.supportsText(text))
                || input.roleIds() == null || input.roleIds().stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入服务器字体支持的单行水印文字（最多 120 字符）并选择有效角色");
        var tenantRoles = roles.findByTenantIdOrderByRoleNameAsc(user.tenantId());
        if (!tenantRoles.stream().map(role -> role.getId()).collect(java.util.stream.Collectors.toSet()).containsAll(input.roleIds()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不属于当前租户");
        jdbc.queryForObject("select id from sys_tenant where id=? for update", String.class, user.tenantId());
        var current = policy(user.tenantId());
        if (current.version() != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已变更，请刷新后再保存");
        var updated = new Policy(input.watermarkEnabled(), text, Set.copyOf(input.roleIds()), current.version() + 1);
        var encoded = json.writeValueAsString(updated.roleIds());
        if (current.version() == 0)
            jdbc.update("insert into report_export_policy(tenant_id,watermark_enabled,watermark,role_ids,version) values(?,?,?,?,?)",
                user.tenantId(), updated.watermarkEnabled(), text, encoded, updated.version());
        else
            jdbc.update("update report_export_policy set watermark_enabled=?,watermark=?,role_ids=?,version=? where tenant_id=?",
                updated.watermarkEnabled(), text, encoded, updated.version(), user.tenantId());
        audit(user, "policy-change", "settings", "success", "policyVersion=" + updated.version());
        return ApiResponse.success(updated);
    }

    @GetMapping("/permission-center/report-exports/audits")
    public ApiResponse<?> records(HttpServletRequest request, @RequestParam(name = "page", defaultValue = "1") int page) {
        var user = manager(request);
        var rows = audits.findAll((root, query, cb) -> cb.and(
            cb.equal(root.get("tenantId"), user.tenantId()), cb.equal(root.get("moduleName"), MODULE)),
            PageRequest.of(Math.max(1, Math.min(100000, page)) - 1, 20, Sort.by("createdAt", "id").descending()));
        return ApiResponse.success(Map.of("items", rows.getContent(), "pages", rows.getTotalPages(), "total", rows.getTotalElements()));
    }

    @PostMapping(value = "/report-exports/pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> pdf(HttpServletRequest request,
            @RequestBody ReportExportSource.Reference reference) {
        var user = identity(request);
        var requestId = UUID.randomUUID().toString();
        if (reference == null || (reference.isArchive()
                ? !validId(reference.reportId()) || reference.conversationId() != null || reference.messageId() != null
                : !validId(reference.conversationId()) || !validId(reference.messageId())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择一份已归档的报告或回答");
        String resource = reference.auditId();
        var policy = policy(user.tenantId());
        if (!allowed(user, policy)) {
            auditPdf(request, user, requestId, resource, policy, "denied", "ROLE_NOT_ALLOWED", null, 0);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色未获报告导出权限");
        }
        ReportPdf.Output result;
        String sourceHash;
        if (!renderSlots.tryAcquire()) {
            auditPdf(request, user, requestId, resource, policy, "failure", "RENDER_CAPACITY_EXHAUSTED", null, 0);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "报告渲染服务繁忙");
        }
        try {
            var source = sources.load(request, reference);
            sourceHash = sha256(json.writeValueAsBytes(List.of(source.entry(),
                source.files().stream().map(file -> List.of(file.path(), file.sha256())).toList())));
            result = ReportPdf.render(printer.print(source), policy.watermarkEnabled() ? policy.watermark() : "");
        } catch (RuntimeScopeAccessDeniedException | SecurityException e) {
            auditPdf(request, user, requestId, resource, policy, "denied", "REPORT_INACCESSIBLE", null, 0);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归档报告不存在或不可访问");
        } catch (ResponseStatusException e) {
            boolean denied = e.getStatusCode().value() == 404 || e.getStatusCode().value() == 403;
            auditPdf(request, user, requestId, resource, policy, denied ? "denied" : "failure",
                denied ? "REPORT_INACCESSIBLE" : "RENDER_UNAVAILABLE", null, 0);
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            auditPdf(request, user, requestId, resource, policy, "failure", "RENDER_FAILED", null, 0);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法生成 PDF，请检查报告大小和内容");
        } finally {
            renderSlots.release();
        }
        // Fail closed if an administrator changed the policy while the document rendered.
        var updated = policy(user.tenantId());
        if (updated.version() != policy.version()) {
            auditPdf(request, user, requestId, resource, policy, "denied", "POLICY_CHANGED", null, 0);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "导出规则已更新，请重试");
        }
        if (!identities.isActiveIdentity(user.tenantId(), user.id())
                || !allowed(identities.getUserView(user.id()), updated)) {
            auditPdf(request, user, requestId, resource, policy, "denied", "IDENTITY_CHANGED", null, 0);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "报告导出授权已失效");
        }
        byte[] pdf = result.bytes();
        auditPdf(request, user, requestId, resource, policy, "success", null, pdf, result.pages(), sourceHash);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Report-Export-Request-Id", requestId)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("report.pdf", StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,128}");
    }

    private Policy policy(String tenant) {
        return jdbc.query("select * from report_export_policy where tenant_id=?", (row, index) -> {
            try {
                return new Policy(row.getBoolean("watermark_enabled"), row.getString("watermark"),
                    json.readValue(row.getString("role_ids"), new TypeReference<Set<String>>() {}), row.getLong("version"));
            } catch (IOException e) {
                throw new IllegalStateException("Invalid report export policy", e);
            }
        }, tenant).stream().findFirst().orElseGet(() -> new Policy(true, "企业内部资料 · 严禁外传", Set.of(), 0));
    }

    private static UserView identity(HttpServletRequest request) {
        if (request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW) instanceof UserView user
                && "enabled".equals(user.status()) && user.tenantId() != null) return user;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录后再操作");
    }

    private UserView manager(HttpServletRequest request) {
        var user = identity(request);
        workspaces.requireAvailable(request, "permissions");
        if (!user.permissionCodes().contains(MANAGE_PERMISSION))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要权限管理授权");
        return user;
    }

    private boolean allowed(UserView user, Policy policy) {
        if (policy.roleIds().isEmpty()) return false;
        var now = java.time.Instant.now();
        return roles.findByTenantIdOrderByRoleNameAsc(user.tenantId()).stream()
            .anyMatch(role -> role.activeAt(now) && policy.roleIds().contains(role.getId())
                && user.roleIds().contains(role.getId()));
    }

    private void auditPdf(HttpServletRequest request, UserView user, String requestId, String reference, Policy policy,
                          String result, String failureReason, byte[] pdf, int pages) {
        auditPdf(request, user, requestId, reference, policy, result, failureReason, pdf, pages, null);
    }

    private void auditPdf(HttpServletRequest request, UserView user, String requestId, String reference, Policy policy,
                          String result, String failureReason, byte[] pdf, int pages, String sourceHash) {
        var detail = json.createObjectNode();
        detail.put("requestId", requestId);
        detail.put("format", "PDF");
        detail.put("sourceIp", request.getRemoteAddr());
        detail.put("policyVersion", policy.version());
        detail.put("generationStatus", pdf != null ? "GENERATED" : "NOT_GENERATED");
        detail.put("transferStatus", pdf != null ? "UNCONFIRMED" : "NOT_STARTED");
        if (failureReason != null) detail.put("reason", failureReason);
        if (pdf != null) {
            detail.put("pages", pages);
            detail.put("sizeBytes", pdf.length);
            detail.put("sha256", sha256(pdf));
            detail.put("sourceManifestSha256", sourceHash);
            if (policy.watermarkEnabled()) detail.put("watermark", policy.watermark());
        }
        audit(user, "pdf", reference, result, detail.toString());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void audit(UserView user, String action, String resource, String result, String detail) {
        var event = new SysAuditLog();
        event.setTenantId(user.tenantId());
        event.setActorId(user.id());
        event.setActorName(user.username());
        event.setModuleName(MODULE);
        event.setActionName(action);
        event.setResourceType("REPORT");
        event.setResourceId(resource);
        event.setResult(result);
        event.setDetail(detail);
        audits.saveAndFlush(event);
    }
}
