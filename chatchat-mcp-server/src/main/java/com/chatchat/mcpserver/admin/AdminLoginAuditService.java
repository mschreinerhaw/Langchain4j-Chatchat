package com.chatchat.mcpserver.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
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
public class AdminLoginAuditService {

    private static final String ACTION_ADMIN_LOGIN = "admin-login";
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

    private final AdminLoginAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String username, HttpServletRequest request) {
        AdminLoginAuditLog auditLog = baseLog(username, request);
        auditLog.setResult("success");
        auditLog.setDetail(detail("success", username, null, request));
        saveQuietly(auditLog);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String username, String reason, HttpServletRequest request) {
        AdminLoginAuditLog auditLog = baseLog(username, request);
        auditLog.setResult("failure");
        auditLog.setDetail(detail("failure", username, reason, request));
        saveQuietly(auditLog);
    }

    public LoginAuditPage search(LoginAuditSearchQuery query) {
        LoginAuditSearchQuery normalized = normalize(query);
        Page<AdminLoginAuditLog> page = repository.findAll(
            spec(normalized),
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

    private AdminLoginAuditLog baseLog(String username, HttpServletRequest request) {
        AdminLoginAuditLog auditLog = new AdminLoginAuditLog();
        auditLog.setUsername(firstText(username, "anonymous"));
        auditLog.setActionName(ACTION_ADMIN_LOGIN);
        auditLog.setIpAddress(clientIp(request));
        auditLog.setMacAddress(clientMac(request));
        auditLog.setUserAgent(header(request, "User-Agent"));
        return auditLog;
    }

    private String detail(String result, String username, String reason, HttpServletRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", result);
        detail.put("action", ACTION_ADMIN_LOGIN);
        detail.put("username", username);
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
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return detail.toString();
        }
    }

    private Specification<AdminLoginAuditLog> spec(LoginAuditSearchQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.actionName() != null) {
                predicates.add(criteriaBuilder.equal(root.get("actionName"), query.actionName()));
            }
            if (query.result() != null) {
                predicates.add(criteriaBuilder.equal(root.get("result"), query.result()));
            }
            if (query.keyword() != null) {
                String keyword = "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("username")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("ipAddress")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("macAddress")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("userAgent")), keyword),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("detail")), keyword)
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private LoginAuditSearchQuery normalize(LoginAuditSearchQuery query) {
        int page = Math.max(1, query == null || query.page() == null ? 1 : query.page());
        int pageSize = Math.min(100, Math.max(10, query == null || query.pageSize() == null ? 20 : query.pageSize()));
        return new LoginAuditSearchQuery(
            page,
            pageSize,
            normalizeAction(query == null ? null : query.actionName()),
            normalizeAllFilter(query == null ? null : query.result()),
            text(query == null ? null : query.keyword())
        );
    }

    private String normalizeAction(String actionName) {
        String value = text(actionName);
        return value == null || "all".equalsIgnoreCase(value) ? ACTION_ADMIN_LOGIN : value;
    }

    private String normalizeAllFilter(String value) {
        String text = text(value);
        return text == null || "all".equalsIgnoreCase(text) ? null : text;
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

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void saveQuietly(AdminLoginAuditLog auditLog) {
        try {
            repository.save(auditLog);
        } catch (Exception ex) {
            log.warn("Failed to write MCP admin login audit username={} result={}: {}",
                auditLog.getUsername(), auditLog.getResult(), ex.getMessage(), ex);
        }
    }

    public record LoginAuditSearchQuery(
        Integer page,
        Integer pageSize,
        String actionName,
        String result,
        String keyword
    ) {
    }

    public record LoginAuditPage(
        List<AdminLoginAuditLog> items,
        int page,
        int pageSize,
        long total,
        int totalPages
    ) {
    }
}
