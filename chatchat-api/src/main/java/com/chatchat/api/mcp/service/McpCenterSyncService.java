package com.chatchat.api.mcp.service;

import com.chatchat.api.mcp.config.McpCenterProperties;
import com.chatchat.api.mcp.entity.McpServiceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpCenterSyncService {

    private static final String PROTOCOL_STREAMABLE_HTTP = "mcp_streamable_http";

    private final McpCenterProperties properties;
    private final McpServiceConfigService configService;
    private final McpToolRegistryBridge registryBridge;
    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.builder().build();

    public SyncResult syncFromCenter() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }

        List<ImportedService> imported = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (properties.isImportStandaloneServer()) {
            try {
                imported.add(importStandaloneServer());
            } catch (Exception ex) {
                errors.add("standalone: " + ex.getMessage());
                log.warn("Failed to import standalone MCP server: {}", ex.getMessage());
            }
        }

        try {
            String adminToken = loginAdmin();
            List<CenterService> centerServices = listCenterServices(adminToken);
            for (CenterService centerService : centerServices) {
                try {
                    imported.add(importCenterService(centerService));
                } catch (Exception ex) {
                    errors.add(centerService.name() + ": " + ex.getMessage());
                    log.warn("Failed to import MCP center service {}: {}", centerService.name(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            errors.add("center-services: " + ex.getMessage());
            log.warn("Failed to read MCP center service list: {}", ex.getMessage());
        }

        registryBridge.refreshRegistry();
        return new SyncResult(imported.size(), imported, errors);
    }

    public CenterStatus status() {
        return new CenterStatus(
            properties.isEnabled(),
            normalizeBaseUrl(properties.getBaseUrl()),
            buildUrl(properties.getBaseUrl(), properties.getMcpEndpoint()),
            properties.isImportStandaloneServer()
        );
    }

    private ImportedService importStandaloneServer() {
        McpServiceConfig config = new McpServiceConfig();
        config.setName(properties.getStandaloneServiceName());
        config.setBaseUrl(buildUrl(properties.getBaseUrl(), properties.getMcpEndpoint()));
        config.setToolDiscoveryPath(properties.getMcpEndpoint());
        config.setToolInvokePath(properties.getMcpEndpoint());
        config.setProtocol(PROTOCOL_STREAMABLE_HTTP);
        config.setEnabled(true);
        config.setTimeoutMs(properties.getTimeoutMs());
        config.setCustomHeadersJson(writeInvocationHeaders(properties.getInvocationToken()));

        McpServiceConfig saved = configService.upsertImported(
            safeImportedId(properties.getStandaloneServiceId()),
            config,
            "mcp_center"
        );
        return toImportedService(saved, "standalone");
    }

    private ImportedService importCenterService(CenterService centerService) {
        McpServiceConfig config = new McpServiceConfig();
        config.setName(centerService.name());
        config.setBaseUrl(centerService.endpoint());
        config.setToolDiscoveryPath("/mcp");
        config.setToolInvokePath("/mcp");
        config.setProtocol(PROTOCOL_STREAMABLE_HTTP);
        config.setEnabled(centerService.enabled() && isActive(centerService.status()));
        config.setTimeoutMs(properties.getTimeoutMs());
        config.setCustomHeadersJson(writeInvocationHeaders(centerService.serviceToken()));

        McpServiceConfig saved = configService.upsertImported(
            safeImportedId("mcp-center-" + centerService.id()),
            config,
            "mcp_center"
        );
        return toImportedService(saved, "registry");
    }

    private String loginAdmin() {
        Map<String, String> payload = Map.of(
            "username", properties.getAdminUsername(),
            "password", properties.getAdminPassword()
        );
        Object raw = webClient.post()
            .uri(buildUrl(properties.getBaseUrl(), properties.getAdminLoginPath()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(Object.class)
            .timeout(Duration.ofMillis(Math.max(1000, properties.getTimeoutMs())))
            .block();

        Object data = unwrapData(raw);
        if (!(data instanceof Map<?, ?> map) || map.get("token") == null) {
            throw new IllegalStateException("MCP center login did not return token");
        }
        return String.valueOf(map.get("token"));
    }

    private List<CenterService> listCenterServices(String adminToken) {
        Object raw = webClient.get()
            .uri(buildUrl(properties.getBaseUrl(), properties.getServiceListPath()))
            .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .retrieve()
            .bodyToMono(Object.class)
            .timeout(Duration.ofMillis(Math.max(1000, properties.getTimeoutMs())))
            .block();

        Object data = unwrapData(raw);
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<CenterService> services = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> source = objectMapper.convertValue(map, new TypeReference<>() {});
            String id = text(source.get("id"));
            String name = text(source.get("name"));
            String endpoint = text(source.get("endpoint"));
            if (id == null || name == null || endpoint == null) {
                continue;
            }
            services.add(new CenterService(
                id,
                name,
                endpoint,
                text(source.get("serviceToken")),
                text(source.get("serviceType")),
                text(source.get("permissionGroup")),
                !Boolean.FALSE.equals(source.get("enabled")),
                text(source.get("status"))
            ));
        }
        return services;
    }

    private Object unwrapData(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("code")) {
                Integer code = toInteger(map.get("code"));
                if (code != null && code != 200) {
                    Object message = map.get("message");
                    throw new IllegalStateException(message == null ? "MCP center error" : String.valueOf(message));
                }
            }
            if (map.containsKey("data")) {
                return map.get("data");
            }
        }
        return raw;
    }

    private ImportedService toImportedService(McpServiceConfig config, String source) {
        return new ImportedService(
            config.getId(),
            config.getName(),
            config.getBaseUrl(),
            config.getProtocol(),
            config.isEnabled(),
            source
        );
    }

    private String writeInvocationHeaders(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-MCP-TOKEN", token.trim());
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize MCP invocation headers", ex);
        }
    }

    private boolean isActive(String status) {
        return status == null || status.isBlank() || "ACTIVE".equalsIgnoreCase(status);
    }

    private String safeImportedId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]+", "-");
        while (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            normalized = "mcp-center-service";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private String buildUrl(String baseUrl, String path) {
        String left = normalizeBaseUrl(baseUrl);
        String right = path == null ? "" : path.trim();
        if (right.isBlank()) {
            return left;
        }
        if (!right.startsWith("/")) {
            right = "/" + right;
        }
        return left + right;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8090" : baseUrl.trim();
        while (normalized.endsWith("/") && normalized.length() > "http://x".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record CenterService(
        String id,
        String name,
        String endpoint,
        String serviceToken,
        String serviceType,
        String permissionGroup,
        boolean enabled,
        String status
    ) {
    }

    public record CenterStatus(
        boolean enabled,
        String baseUrl,
        String standaloneMcpEndpoint,
        boolean importStandaloneServer
    ) {
    }

    public record ImportedService(
        String id,
        String name,
        String baseUrl,
        String protocol,
        boolean enabled,
        String source
    ) {
    }

    public record SyncResult(
        int importedCount,
        List<ImportedService> importedServices,
        List<String> errors
    ) {
    }
}
