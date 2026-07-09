package com.chatchat.api.enterprise;

import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAuditService {

    private static final List<String> LOGIN_ACTIONS = List.of("login", "embed-login");
    private static final List<String> CLIENT_IP_HEADERS = List.of(
        "X-Forwarded-For",
        "X-Real-IP",
        "Proxy-Client-IP",
        "WL-Proxy-Client-IP",
        "HTTP_CLIENT_IP",
        "HTTP_X_FORWARDED_FOR"
    );
    private static final List<String> CLIENT_MAC_HEADERS = List.of(
        "X-Client-Mac",
        "X-Mac-Address",
        "X-Device-Mac",
        "X-Forwarded-Mac",
        "X-Real-Mac"
    );

    private final SysAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String actionName, String attemptedUsername, EnterpriseAdminService.AuthResult result, HttpServletRequest request) {
        EnterpriseAdminService.UserView user = result == null ? null : result.user();
        SysAuditLog log = baseLog(actionName, attemptedUsername, request);
        if (user != null) {
            log.setTenantId(user.tenantId());
            log.setActorId(user.id());
            log.setActorName(firstText(user.displayName(), user.username(), attemptedUsername));
            log.setResourceId(user.id());
        }
        log.setResult("success");
        log.setDetail(detail("success", actionName, attemptedUsername, null, result, request));
        saveQuietly(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String actionName, String attemptedUsername, String reason, HttpServletRequest request) {
        SysAuditLog log = baseLog(actionName, attemptedUsername, request);
        log.setResult("failure");
        log.setDetail(detail("failure", actionName, attemptedUsername, reason, null, request));
        saveQuietly(log);
    }

    public LoginAuditPage searchLoginAudits(LoginAuditSearchQuery query) {
        LoginAuditSearchQuery normalized = normalize(query);
        Page<SysAuditLog> page = auditLogRepository.findAll(
            loginAuditSpec(normalized),
            PageRequest.of(normalized.page() - 1, normalized.pageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new LoginAuditPage(
            page.getContent(),
            normalized.page(),
            normalized.pageSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }

    private SysAuditLog baseLog(String actionName, String attemptedUsername, HttpServletRequest request) {
        SysAuditLog log = new SysAuditLog();
        log.setActorName(firstText(attemptedUsername, "anonymous"));
        log.setModuleName("auth");
        log.setActionName(normalizeAction(actionName));
        log.setResourceType("sys_user");
        log.setResourceId(firstText(attemptedUsername, clientIp(request)));
        return log;
    }

    private String detail(
        String result,
        String actionName,
        String attemptedUsername,
        String reason,
        EnterpriseAdminService.AuthResult authResult,
        HttpServletRequest request
    ) {
        EnterpriseAdminService.UserView user = authResult == null ? null : authResult.user();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", result);
        detail.put("action", normalizeAction(actionName));
        detail.put("attemptedUsername", attemptedUsername);
        detail.put("userId", user == null ? null : user.id());
        detail.put("username", user == null ? null : user.username());
        detail.put("displayName", user == null ? null : user.displayName());
        detail.put("tenantId", user == null ? null : user.tenantId());
        detail.put("embedded", authResult != null && authResult.embedded());
        detail.put("ipAddress", clientIp(request));
        detail.put("macAddress", clientMac(request));
        detail.put("userAgent", header(request, "User-Agent"));
        detail.put("forwardedFor", header(request, "X-Forwarded-For"));
        detail.put("realIp", header(request, "X-Real-IP"));
        detail.put("remoteAddr", request == null ? null : request.getRemoteAddr());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason);
        }
        try {
            return truncate(objectMapper.writeValueAsString(detail), 4000);
        } catch (JsonProcessingException ex) {
            return truncate(detail.toString(), 4000);
        }
    }

    private String clientIp(HttpServletRequest request) {
        for (String header : CLIENT_IP_HEADERS) {
            String value = header(request, header);
            String ip = firstForwardedValue(value);
            if (ip != null) {
                return ip;
            }
        }
        return request == null ? null : request.getRemoteAddr();
    }

    private String clientMac(HttpServletRequest request) {
        for (String header : CLIENT_MAC_HEADERS) {
            String value = header(request, header);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstForwardedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank() && !"unknown".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    private String header(HttpServletRequest request, String name) {
        String value = request == null ? null : request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeAction(String actionName) {
        return actionName == null || actionName.isBlank() ? "login" : actionName.trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private void saveQuietly(SysAuditLog auditLog) {
        try {
            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.warn("Failed to write login audit action={} actor={} result={}: {}",
                auditLog.getActionName(), auditLog.getActorName(), auditLog.getResult(), ex.getMessage(), ex);
        }
    }

    private LoginAuditSearchQuery normalize(LoginAuditSearchQuery query) {
        int page = Math.max(1, query == null || query.page() == null ? 1 : query.page());
        int pageSize = Math.min(100, Math.max(10, query == null || query.pageSize() == null ? 20 : query.pageSize()));
        return new LoginAuditSearchQuery(
            page,
            pageSize,
            text(query == null ? null : query.tenantId()),
            normalizeActionFilter(query == null ? null : query.actionName()),
            normalizeAllFilter(query == null ? null : query.result()),
            text(query == null ? null : query.keyword())
        );
    }

    private Specification<SysAuditLog> loginAuditSpec(LoginAuditSearchQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("moduleName"), "auth"));
            predicates.add(root.get("actionName").in(LOGIN_ACTIONS));
            if (query.tenantId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("tenantId"), query.tenantId()));
            }
            if (query.actionName() != null) {
                predicates.add(criteriaBuilder.equal(root.get("actionName"), query.actionName()));
            }
            if (query.result() != null) {
                predicates.add(criteriaBuilder.equal(root.get("result"), query.result()));
            }
            if (query.keyword() != null) {
                String keyword = "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("actorId")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("actorName")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("resourceId")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("detail")), keyword)
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private String normalizeActionFilter(String actionName) {
        String value = text(actionName);
        return value == null || "all".equalsIgnoreCase(value) ? null : normalizeAction(value);
    }

    private String normalizeAllFilter(String value) {
        String text = text(value);
        return text == null || "all".equalsIgnoreCase(text) ? null : text;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record LoginAuditSearchQuery(
        Integer page,
        Integer pageSize,
        String tenantId,
        String actionName,
        String result,
        String keyword
    ) {
    }

    public record LoginAuditPage(
        List<SysAuditLog> items,
        int page,
        int pageSize,
        long total,
        int totalPages
    ) {
    }
}
