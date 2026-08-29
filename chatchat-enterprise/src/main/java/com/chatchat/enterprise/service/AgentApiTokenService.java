package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.identity.SysUserRole;
import com.chatchat.enterprise.entity.security.AgentApiToken;
import com.chatchat.enterprise.entity.security.RoleAgentBinding;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.AgentApiTokenRepository;
import com.chatchat.enterprise.repository.security.RoleAgentBindingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;

@Service
@RequiredArgsConstructor
public class AgentApiTokenService {

    public static final String TOKEN_PREFIX = "ccat_";
    private static final long DEFAULT_EXPIRY_SECONDS = Duration.ofDays(30).toSeconds();
    private static final long MAX_EXPIRY_SECONDS = Duration.ofDays(3650).toSeconds();

    private final AgentApiTokenRepository tokenRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final RoleAgentBindingRepository roleAgentBindingRepository;
    private final SysTenantRepository tenantRepository;
    private final SysAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public List<TokenView> list(String operatorUserId, String userId) {
        requirePlatformAdmin(operatorUserId);
        List<AgentApiToken> records = userId == null || userId.isBlank()
            ? tokenRepository.findAllByOrderByCreatedAtDesc()
            : tokenRepository.findByUserIdOrderByCreatedAtDesc(userId.trim());
        return records.stream().map(this::toView).toList();
    }

    @Transactional
    public IssuedToken create(String operatorUserId, CreateTokenRequest request) {
        SysUser operator = requirePlatformAdmin(operatorUserId);
        String targetUserId = requireText(request == null ? null : request.userId(), "userId");
        SysUser target = requireEligibleTarget(targetUserId);
        Instant expiresAt = resolveExpiry(request == null ? null : request.permanent(),
            request == null ? null : request.expiresInSeconds(), null);
        String rawToken = generateRawToken();
        AgentApiToken record = new AgentApiToken();
        record.setTokenHash(hash(rawToken));
        record.setTokenPreview(preview(rawToken));
        record.setTenantId(target.getTenantId());
        record.setUserId(target.getId());
        record.setUsername(target.getUsername());
        record.setDisplayName(target.getDisplayName());
        record.setTokenName(normalizeName(request == null ? null : request.tokenName(), target));
        record.setStatus("active");
        record.setExpiresAt(expiresAt);
        record.setCreatedBy(operator.getId());
        record.setCreatedByName(operator.getDisplayName());
        AgentApiToken saved = tokenRepository.save(record);
        audit(operator, "agent-api-token-create", saved, expiryDetail(expiresAt));
        return new IssuedToken(toView(saved), rawToken);
    }

    @Transactional
    public TokenView revoke(String operatorUserId, String tokenId) {
        SysUser operator = requirePlatformAdmin(operatorUserId);
        AgentApiToken record = requireToken(tokenId);
        Instant now = Instant.now();
        record.setStatus("revoked");
        record.setRevokedAt(now);
        record.setRevokedBy(operator.getId());
        AgentApiToken saved = tokenRepository.save(record);
        audit(operator, "agent-api-token-revoke", saved, "revoked");
        return toView(saved);
    }

    @Transactional
    public IssuedToken reset(String operatorUserId, String tokenId, ResetTokenRequest request) {
        SysUser operator = requirePlatformAdmin(operatorUserId);
        AgentApiToken record = requireToken(tokenId);
        SysUser target = requireEligibleTarget(record.getUserId());
        String rawToken = generateRawToken();
        Instant expiresAt = resolveExpiry(request == null ? null : request.permanent(),
            request == null ? null : request.expiresInSeconds(), record.getExpiresAt());
        record.setTokenHash(hash(rawToken));
        record.setTokenPreview(preview(rawToken));
        record.setTenantId(target.getTenantId());
        record.setUsername(target.getUsername());
        record.setDisplayName(target.getDisplayName());
        record.setStatus("active");
        record.setExpiresAt(expiresAt);
        record.setRevokedAt(null);
        record.setRevokedBy(null);
        record.setRotatedAt(Instant.now());
        record.setLastUsedAt(null);
        record.setLastUsedIp(null);
        record.setLastUsedPath(null);
        record.setUsedCount(0L);
        AgentApiToken saved = tokenRepository.save(record);
        audit(operator, "agent-api-token-reset", saved, expiryDetail(expiresAt));
        return new IssuedToken(toView(saved), rawToken);
    }

    @Transactional
    public Authentication authenticate(String rawToken, String ipAddress, String requestPath) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String tokenHash = hash(rawToken.trim());
        AgentApiToken record = tokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (record == null) {
            auditFailure("unknown", preview(rawToken), requestPath, "token not found");
            return null;
        }
        Instant now = Instant.now();
        if (!"active".equalsIgnoreCase(record.getStatus())
            || (record.getExpiresAt() != null && !record.getExpiresAt().isAfter(now))) {
            auditFailure(record.getTenantId(), record.getTokenPreview(), requestPath, "token revoked or expired");
            return null;
        }
        SysUser user = userRepository.findById(record.getUserId()).orElse(null);
        if (user == null || !"enabled".equalsIgnoreCase(user.getStatus())
            || !Objects.equals(user.getTenantId(), record.getTenantId())) {
            auditFailure(record.getTenantId(), record.getTokenPreview(), requestPath, "user unavailable");
            return null;
        }
        tokenRepository.recordUse(record.getId(), now, truncate(ipAddress, 128), truncate(requestPath, 512));
        auditUse(record, user, ipAddress, requestPath);
        return new Authentication(record.getId(), user.getId(), user.getUsername(), user.getTenantId());
    }

    public boolean looksLikeApiToken(String token) {
        return token != null && token.startsWith(TOKEN_PREFIX);
    }

    private SysUser requirePlatformAdmin(String userId) {
        SysUser user = userRepository.findById(requireText(userId, "operatorUserId"))
            .orElseThrow(() -> new IllegalArgumentException("operator not found"));
        boolean platformTenant = tenantRepository.findById(user.getTenantId())
            .map(SysTenant::getTenantNo)
            .map(number -> number == PLATFORM_TENANT_NO)
            .orElse(false);
        if (!platformTenant || !"admin".equalsIgnoreCase(user.getUsername())
            || !"enabled".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("only the platform administrator can manage Agent API tokens");
        }
        return user;
    }

    private SysUser requireEligibleTarget(String userId) {
        SysUser user = userRepository.findById(requireText(userId, "userId"))
            .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!"enabled".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("disabled users cannot receive Agent API tokens");
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return user;
        }
        Instant now = Instant.now();
        List<String> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
            .filter(role -> user.getTenantId().equals(role.getTenantId()))
            .map(SysUserRole::getRoleId)
            .toList();
        List<String> activeRoleIds = roleRepository.findAllById(roleIds).stream()
            .filter(role -> user.getTenantId().equals(role.getTenantId()))
            .filter(role -> "enabled".equalsIgnoreCase(role.getStatus()))
            .map(role -> role.getId())
            .toList();
        boolean hasAgentRole = !activeRoleIds.isEmpty()
            && roleAgentBindingRepository.findByRoleIdIn(activeRoleIds).stream()
            .filter(RoleAgentBinding::isEnabled)
            .filter(binding -> user.getTenantId().equals(binding.getTenantId()))
            .anyMatch(binding -> (binding.getEffectiveTime() == null || !binding.getEffectiveTime().isAfter(now))
                && (binding.getExpireTime() == null || binding.getExpireTime().isAfter(now)));
        if (!hasAgentRole) {
            throw new IllegalArgumentException("user has no active role-to-Agent authorization");
        }
        return user;
    }

    private AgentApiToken requireToken(String tokenId) {
        return tokenRepository.findById(requireText(tokenId, "tokenId"))
            .orElseThrow(() -> new IllegalArgumentException("Agent API token not found"));
    }

    private Instant resolveExpiry(Boolean permanent, Long expiresInSeconds, Instant currentExpiry) {
        if (Boolean.TRUE.equals(permanent)) {
            return null;
        }
        if (expiresInSeconds == null) {
            if (currentExpiry != null && currentExpiry.isAfter(Instant.now())) {
                return currentExpiry;
            }
            return Instant.now().plusSeconds(DEFAULT_EXPIRY_SECONDS);
        }
        if (expiresInSeconds < 60 || expiresInSeconds > MAX_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("expiresInSeconds must be between 60 seconds and 3650 days");
        }
        return Instant.now().plusSeconds(expiresInSeconds);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String preview(String token) {
        if (token == null || token.length() <= 16) {
            return token;
        }
        return token.substring(0, 10) + "..." + token.substring(token.length() - 6);
    }

    private String normalizeName(String value, SysUser target) {
        String normalized = value == null ? "" : value.trim();
        return truncate(normalized.isBlank() ? target.getUsername() + " Agent API" : normalized, 128);
    }

    private TokenView toView(AgentApiToken record) {
        boolean timeExpired = record.getExpiresAt() != null && !record.getExpiresAt().isAfter(Instant.now());
        String effectiveStatus = timeExpired && "active".equalsIgnoreCase(record.getStatus())
            ? "expired" : record.getStatus();
        return new TokenView(record.getId(), record.getTokenPreview(), record.getTokenName(),
            record.getTenantId(), record.getUserId(), record.getUsername(), record.getDisplayName(),
            effectiveStatus, record.getExpiresAt() == null, record.getExpiresAt(), record.getLastUsedAt(),
            record.getLastUsedIp(), record.getLastUsedPath(), record.getUsedCount(), record.getCreatedByName(),
            record.getCreatedAt(), record.getUpdatedAt(), record.getRevokedAt(), record.getRotatedAt());
    }

    private void audit(SysUser operator, String action, AgentApiToken token, String detail) {
        SysAuditLog log = new SysAuditLog();
        log.setTenantId(token.getTenantId());
        log.setActorId(operator.getId());
        log.setActorName(operator.getDisplayName());
        log.setModuleName("auth");
        log.setActionName(action);
        log.setResourceType("agent_api_token");
        log.setResourceId(token.getId());
        log.setDetail("target=" + token.getUsername() + ", token=" + token.getTokenPreview() + ", " + detail);
        log.setResult("success");
        auditLogRepository.save(log);
    }

    private void auditUse(AgentApiToken token, SysUser user, String ipAddress, String requestPath) {
        SysAuditLog log = new SysAuditLog();
        log.setTenantId(user.getTenantId());
        log.setActorId(user.getId());
        log.setActorName(user.getDisplayName());
        log.setModuleName("auth");
        log.setActionName("agent-api-token-authenticate");
        log.setResourceType("agent_api_token");
        log.setResourceId(token.getId());
        log.setDetail(authenticationAuditDetail(token.getTokenPreview(), user.getUsername(), ipAddress,
            requestPath, null));
        log.setResult("success");
        auditLogRepository.save(log);
    }

    private void auditFailure(String tenantId, String tokenPreview, String requestPath, String reason) {
        SysAuditLog log = new SysAuditLog();
        log.setTenantId("unknown".equals(tenantId) ? null : tenantId);
        log.setActorName("Agent API token");
        log.setModuleName("auth");
        log.setActionName("agent-api-token-authenticate");
        log.setResourceType("agent_api_token");
        log.setDetail(authenticationAuditDetail(tokenPreview, null, null, requestPath, reason));
        log.setResult("failure");
        auditLogRepository.save(log);
    }

    private String expiryDetail(Instant expiresAt) {
        return expiresAt == null ? "permanent" : "expiresAt=" + expiresAt;
    }

    private String authenticationAuditDetail(String tokenPreview, String username, String ipAddress,
                                             String requestPath, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tokenPreview", tokenPreview);
        detail.put("username", username);
        detail.put("ipAddress", truncate(ipAddress, 128));
        detail.put("requestPath", truncate(requestPath, 512));
        detail.put("reason", reason);
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return detail.toString();
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record CreateTokenRequest(String userId, String tokenName, Boolean permanent, Long expiresInSeconds) {
    }

    public record ResetTokenRequest(Boolean permanent, Long expiresInSeconds) {
    }

    public record IssuedToken(TokenView token, String secret) {
    }

    public record Authentication(String tokenId, String userId, String username, String tenantId) {
    }

    public record TokenView(
        String id, String tokenPreview, String tokenName, String tenantId, String userId, String username,
        String displayName, String status, boolean permanent, Instant expiresAt, Instant lastUsedAt,
        String lastUsedIp, String lastUsedPath, long usedCount, String createdByName, Instant createdAt,
        Instant updatedAt, Instant revokedAt, Instant rotatedAt
    ) {
    }
}
