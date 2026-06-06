package com.chatchat.api.mcp.service;

import com.chatchat.api.mcp.entity.McpServiceConfig;
import com.chatchat.api.mcp.entity.McpServiceConfigVersion;
import com.chatchat.api.mcp.repository.McpServiceConfigRepository;
import com.chatchat.api.mcp.repository.McpServiceConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * MCP service configuration CRUD operations.
 */
@Service
@RequiredArgsConstructor
public class McpServiceConfigService {

    private static final String PROTOCOL_STREAMABLE_HTTP = "mcp_streamable_http";
    private static final String PROTOCOL_STDIO_PROXY = "mcp_stdio_proxy";

    private final McpServiceConfigRepository repository;
    private final McpServiceConfigVersionRepository versionRepository;

    @Transactional(readOnly = true)
    public List<McpServiceConfig> listAll() {
        return repository.findAll().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<McpServiceConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public McpServiceConfig getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("MCP service config not found: " + id));
    }

    @Transactional
    public McpServiceConfig create(McpServiceConfig draft) {
        validate(draft);
        McpServiceConfig saved = repository.save(draft);
        snapshotVersion(saved, "create");
        return saved;
    }

    @Transactional
    public McpServiceConfig update(String id, McpServiceConfig draft) {
        McpServiceConfig current = getById(id);
        current.setName(draft.getName());
        current.setBaseUrl(draft.getBaseUrl());
        current.setToolDiscoveryPath(draft.getToolDiscoveryPath());
        current.setToolInvokePath(draft.getToolInvokePath());
        current.setAuthToken(blankToNull(draft.getAuthToken()));
        current.setCustomHeadersJson(blankToNull(draft.getCustomHeadersJson()));
        current.setProtocol(defaultProtocol(draft.getProtocol()));
        current.setStdioCommand(blankToNull(draft.getStdioCommand()));
        current.setStdioArgsJson(blankToNull(draft.getStdioArgsJson()));
        current.setStdioEnvJson(blankToNull(draft.getStdioEnvJson()));
        current.setStdioWorkingDirectory(blankToNull(draft.getStdioWorkingDirectory()));
        current.setProxyEnabled(draft.isProxyEnabled());
        current.setProxyType(defaultProxyType(draft.getProxyType()));
        current.setProxyHost(blankToNull(draft.getProxyHost()));
        current.setProxyPort(normalizeProxyPort(draft.getProxyPort()));
        current.setProxyUsername(blankToNull(draft.getProxyUsername()));
        current.setProxyPassword(blankToNull(draft.getProxyPassword()));
        current.setEnabled(draft.isEnabled());
        current.setTimeoutMs(draft.getTimeoutMs() <= 0 ? 20000 : draft.getTimeoutMs());
        validate(current);
        McpServiceConfig saved = repository.save(current);
        snapshotVersion(saved, "update");
        return saved;
    }

    @Transactional
    public McpServiceConfig setEnabled(String id, boolean enabled) {
        McpServiceConfig current = getById(id);
        current.setEnabled(enabled);
        McpServiceConfig saved = repository.save(current);
        snapshotVersion(saved, enabled ? "enable" : "disable");
        return saved;
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            return;
        }
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<McpServiceVersionSnapshot> listVersions(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId is required");
        }
        return versionRepository.findTop30ByServiceIdOrderByCreatedAtDesc(serviceId.trim()).stream()
            .map(this::toVersionSnapshot)
            .toList();
    }

    @Transactional
    public McpServiceConfig rollbackToVersion(String serviceId, String versionId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId is required");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId is required");
        }
        McpServiceConfig current = getById(serviceId.trim());
        McpServiceConfigVersion target = versionRepository.findById(versionId.trim())
            .orElseThrow(() -> new IllegalArgumentException("MCP service version not found: " + versionId));
        if (!current.getId().equals(target.getServiceId())) {
            throw new IllegalArgumentException("version does not belong to service: " + serviceId);
        }
        snapshotVersion(current, "before_rollback");
        current.setName(target.getName());
        current.setBaseUrl(target.getBaseUrl());
        current.setToolDiscoveryPath(target.getToolDiscoveryPath());
        current.setToolInvokePath(target.getToolInvokePath());
        current.setAuthToken(target.getAuthToken());
        current.setCustomHeadersJson(target.getCustomHeadersJson());
        current.setProtocol(target.getProtocol());
        current.setStdioCommand(target.getStdioCommand());
        current.setStdioArgsJson(target.getStdioArgsJson());
        current.setStdioEnvJson(target.getStdioEnvJson());
        current.setStdioWorkingDirectory(target.getStdioWorkingDirectory());
        current.setProxyEnabled(target.isProxyEnabled());
        current.setProxyType(target.getProxyType());
        current.setProxyHost(target.getProxyHost());
        current.setProxyPort(target.getProxyPort());
        current.setProxyUsername(target.getProxyUsername());
        current.setProxyPassword(target.getProxyPassword());
        current.setEnabled(target.isEnabled());
        current.setTimeoutMs(target.getTimeoutMs() <= 0 ? 20000 : target.getTimeoutMs());
        validate(current);
        McpServiceConfig saved = repository.save(current);
        snapshotVersion(saved, "rollback");
        return saved;
    }

    private void validate(McpServiceConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("MCP service name is required");
        }
        String protocol = defaultProtocol(config.getProtocol());
        config.setProtocol(protocol);
        boolean stdioProxy = PROTOCOL_STDIO_PROXY.equalsIgnoreCase(protocol);
        boolean streamableHttp = PROTOCOL_STREAMABLE_HTTP.equalsIgnoreCase(protocol);
        if (!stdioProxy && (config.getBaseUrl() == null || config.getBaseUrl().isBlank())) {
            throw new IllegalArgumentException("MCP baseUrl is required");
        }
        if (stdioProxy && (config.getStdioCommand() == null || config.getStdioCommand().isBlank())) {
            throw new IllegalArgumentException("stdioCommand is required when protocol is mcp_stdio_proxy");
        }
        config.setToolDiscoveryPath(defaultPath(config.getToolDiscoveryPath(), streamableHttp ? "/mcp" : "/tools"));
        config.setToolInvokePath(defaultPath(config.getToolInvokePath(), streamableHttp ? "/mcp" : "/tools/call"));
        config.setStdioCommand(blankToNull(config.getStdioCommand()));
        config.setStdioArgsJson(blankToNull(config.getStdioArgsJson()));
        config.setStdioEnvJson(blankToNull(config.getStdioEnvJson()));
        config.setStdioWorkingDirectory(blankToNull(config.getStdioWorkingDirectory()));
        config.setAuthToken(blankToNull(config.getAuthToken()));
        config.setCustomHeadersJson(blankToNull(config.getCustomHeadersJson()));
        config.setProxyType(defaultProxyType(config.getProxyType()));
        config.setProxyHost(blankToNull(config.getProxyHost()));
        config.setProxyPort(normalizeProxyPort(config.getProxyPort()));
        config.setProxyUsername(blankToNull(config.getProxyUsername()));
        config.setProxyPassword(blankToNull(config.getProxyPassword()));
        if (config.isProxyEnabled()) {
            if (config.getProxyHost() == null || config.getProxyHost().isBlank() ||
                config.getProxyPort() == null) {
                throw new IllegalArgumentException("MCP proxy host and port are required when proxy is enabled");
            }
        }
        if (config.getTimeoutMs() <= 0) {
            config.setTimeoutMs(20000);
        }
        if (stdioProxy && (config.getBaseUrl() == null || config.getBaseUrl().isBlank())) {
            config.setBaseUrl("stdio://local");
        }
    }

    private String defaultPath(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (isAbsoluteHttpUrl(trimmed)) {
            return trimmed;
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private boolean isAbsoluteHttpUrl(String value) {
        String lowered = value.toLowerCase();
        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String defaultProtocol(String value) {
        String protocol = blankToNull(value);
        if (protocol == null) {
            return "legacy_http";
        }
        String normalized = protocol.toLowerCase(Locale.ROOT);
        if (normalized.equals(PROTOCOL_STREAMABLE_HTTP)
            || normalized.equals("stateless")
            || normalized.equals("stateful")
            || normalized.equals("streamable_http")
            || normalized.equals("streamable-http")) {
            return PROTOCOL_STREAMABLE_HTTP;
        }
        if (normalized.equals("stdio_proxy")) {
            return PROTOCOL_STDIO_PROXY;
        }
        return normalized;
    }

    private String defaultProxyType(String value) {
        String type = blankToNull(value);
        return type == null ? "http" : type.toLowerCase();
    }

    private Integer normalizeProxyPort(Integer port) {
        if (port == null) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("MCP proxy port must be between 1 and 65535");
        }
        return port;
    }

    private void snapshotVersion(McpServiceConfig source, String action) {
        if (source == null || source.getId() == null || source.getId().isBlank()) {
            return;
        }
        McpServiceConfigVersion version = new McpServiceConfigVersion();
        version.setServiceId(source.getId());
        version.setAction(action == null ? "update" : action.trim());
        version.setName(source.getName());
        version.setBaseUrl(source.getBaseUrl());
        version.setToolDiscoveryPath(source.getToolDiscoveryPath());
        version.setToolInvokePath(source.getToolInvokePath());
        version.setAuthToken(source.getAuthToken());
        version.setCustomHeadersJson(source.getCustomHeadersJson());
        version.setProtocol(source.getProtocol());
        version.setStdioCommand(source.getStdioCommand());
        version.setStdioArgsJson(source.getStdioArgsJson());
        version.setStdioEnvJson(source.getStdioEnvJson());
        version.setStdioWorkingDirectory(source.getStdioWorkingDirectory());
        version.setProxyEnabled(source.isProxyEnabled());
        version.setProxyType(source.getProxyType());
        version.setProxyHost(source.getProxyHost());
        version.setProxyPort(source.getProxyPort());
        version.setProxyUsername(source.getProxyUsername());
        version.setProxyPassword(source.getProxyPassword());
        version.setEnabled(source.isEnabled());
        version.setTimeoutMs(source.getTimeoutMs());
        versionRepository.save(version);
    }

    private McpServiceVersionSnapshot toVersionSnapshot(McpServiceConfigVersion entity) {
        return new McpServiceVersionSnapshot(
            entity.getId(),
            entity.getServiceId(),
            entity.getAction(),
            entity.getName(),
            entity.getProtocol(),
            entity.getBaseUrl(),
            entity.isEnabled(),
            entity.getToolDiscoveryPath(),
            entity.getToolInvokePath(),
            entity.getTimeoutMs(),
            entity.getCreatedAt() == null ? Instant.EPOCH.toEpochMilli() : entity.getCreatedAt().toEpochMilli()
        );
    }

    public record McpServiceVersionSnapshot(
        String id,
        String serviceId,
        String action,
        String name,
        String protocol,
        String baseUrl,
        boolean enabled,
        String toolDiscoveryPath,
        String toolInvokePath,
        int timeoutMs,
        Long createdAt
    ) {
    }
}
