package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

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
    public ApiResponse<?> records(HttpServletRequest request, @RequestParam(defaultValue = "1") int page) {
        var user = manager(request);
        var rows = audits.findAll((root, query, cb) -> cb.and(
            cb.equal(root.get("tenantId"), user.tenantId()), cb.equal(root.get("moduleName"), MODULE)),
            PageRequest.of(Math.max(1, Math.min(100000, page)) - 1, 20, Sort.by("createdAt", "id").descending()));
        return ApiResponse.success(Map.of("items", rows.getContent(), "pages", rows.getTotalPages(), "total", rows.getTotalElements()));
    }

    @PostMapping(value = "/report-exports/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> pdf(HttpServletRequest request,
            @RequestParam(defaultValue = "report") String reference,
            @RequestParam(name = "pages") List<MultipartFile> pages) {
        var user = identity(request);
        var requestId = UUID.randomUUID().toString();
        String resource = reference.matches("[A-Za-z0-9_-]{1,64}") ? reference : "report";
        var policy = policy(user.tenantId());
        if (!allowed(user, policy)) {
            auditPdf(request, user, requestId, resource, policy, "denied", "ROLE_NOT_ALLOWED", null, 0);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色未获报告导出权限");
        }
        byte[] pdf;
        try {
            pdf = ReportPdf.render(pages, policy.watermarkEnabled() ? policy.watermark() : "");
        } catch (IOException | IllegalArgumentException e) {
            auditPdf(request, user, requestId, resource, policy, "failure", "RENDER_FAILED", null, 0);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法生成 PDF，请检查报告大小和内容");
        }
        // Fail closed if an administrator changed the policy while the document rendered.
        var updated = policy(user.tenantId());
        if (updated.version() != policy.version()) {
            auditPdf(request, user, requestId, resource, policy, "denied", "POLICY_CHANGED", null, 0);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "导出规则已更新，请重试");
        }
        auditPdf(request, user, requestId, resource, policy, "success", null, pdf, pages.size());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Report-Export-Request-Id", requestId)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("report.pdf", StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }

    private Policy policy(String tenant) {
        return jdbc.query("select * from report_export_policy where tenant_id=?", (row, index) -> {
            try {
                return new Policy(row.getBoolean("watermark_enabled"), row.getString("watermark"),
                    json.readValue(row.getString("role_ids"), new TypeReference<Set<String>>() {}), row.getLong("version"));
            } catch (IOException e) {
                throw new IllegalStateException("Invalid report export policy", e);
            }
        }, tenant).stream().findFirst().orElseGet(() -> new Policy(true, "Internal", Set.of(), 0));
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
        if (policy.version() == 0) return user.permissionCodes().contains(MANAGE_PERMISSION);
        return roles.findByTenantIdOrderByRoleNameAsc(user.tenantId()).stream()
            .anyMatch(role -> "enabled".equals(role.getStatus()) && policy.roleIds().contains(role.getId())
                && user.roleIds().contains(role.getId()));
    }

    private void auditPdf(HttpServletRequest request, UserView user, String requestId, String reference, Policy policy,
                          String result, String failureReason, byte[] pdf, int pages) {
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
