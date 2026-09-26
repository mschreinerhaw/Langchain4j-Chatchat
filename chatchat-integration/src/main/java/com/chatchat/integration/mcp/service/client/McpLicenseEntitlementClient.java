package com.chatchat.integration.mcp.service.client;

import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Reads signed runtime entitlements from the MCP server without exposing the installed license to the API. */
@Service
@RequiredArgsConstructor
public class McpLicenseEntitlementClient implements McpLicenseEntitlementPort {

    static final String AGENT_LIMIT_PATH = "/internal/v1/license/agent-publication-limit";
    static final String SKILL_LIMIT_PATH = "/internal/v1/license/skill-publication-limit";
    static final String SKILL_FEDERATION_PATH = "/internal/v1/license/skill-federation-entitlement";

    private final McpCenterProperties properties;
    private final InternalCredentialProperties credentials;
    private final WebClient webClient = WebClient.builder().build();

    @Override
    public AgentPublicationLimit agentPublicationLimit() {
        try {
            Map<?, ?> data = request(AGENT_LIMIT_PATH);
            Integer maximum = integer(data.get("maxPublishedAgents"));
            boolean licenseValid = booleanValue(data.get("licenseValid"));
            boolean limited = booleanValue(data.get("limited"));
            if (licenseValid && (maximum == null || (limited && maximum <= 0))) {
                return McpLicenseEntitlementPort.super.agentPublicationLimit();
            }
            return new AgentPublicationLimit(
                licenseValid,
                text(data.get("licenseStatus")),
                text(data.get("message")),
                maximum == null ? 5 : maximum,
                !licenseValid || limited
            );
        } catch (RuntimeException unavailable) {
            return McpLicenseEntitlementPort.super.agentPublicationLimit();
        }
    }

    @Override
    public SkillPublicationLimit skillPublicationLimit() {
        try {
            Map<?, ?> data = request(SKILL_LIMIT_PATH);
            Integer maximum = integer(data.get("maxPublishedSkills"));
            if (maximum == null) {
                maximum = integer(data.get("maxSkills"));
            }
            boolean licenseValid = booleanValue(data.get("licenseValid"));
            boolean limited = booleanValue(data.get("limited"));
            if (licenseValid && (maximum == null || (limited && maximum <= 0))) {
                return McpLicenseEntitlementPort.super.skillPublicationLimit();
            }
            return new SkillPublicationLimit(
                licenseValid,
                text(data.get("licenseStatus")),
                text(data.get("message")),
                maximum == null ? 5 : maximum,
                !licenseValid || limited,
                "MCP"
            );
        } catch (RuntimeException unavailable) {
            return McpLicenseEntitlementPort.super.skillPublicationLimit();
        }
    }

    @Override
    public SkillFederationEntitlement skillFederationEntitlement() {
        try {
            Map<?, ?> data = request(SKILL_FEDERATION_PATH);
            return new SkillFederationEntitlement(
                booleanValue(data.get("allowed")), text(data.get("licenseStatus")),
                text(data.get("message")), "MCP");
        } catch (RuntimeException unavailable) {
            return McpLicenseEntitlementPort.super.skillFederationEntitlement();
        }
    }

    private Map<?, ?> request(String path) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = InternalRequestSigner.sign(credentials.resolvedSecret(), "GET", path, timestamp, nonce);
        Object raw = webClient.get()
            .uri(baseUrl() + path)
            .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
            .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
            .header(InternalRequestSigner.NONCE_HEADER, nonce)
            .header(InternalRequestSigner.SIGNATURE_HEADER, signature)
            .retrieve()
            .bodyToMono(Object.class)
            .timeout(Duration.ofMillis(Math.max(1000, timeoutMs())))
            .block();
        return unwrapData(raw);
    }

    private Map<?, ?> unwrapData(Object raw) {
        if (!(raw instanceof Map<?, ?> response)) {
            throw new IllegalStateException("MCP License entitlement response is empty");
        }
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 200) {
            throw new IllegalStateException(text(response.get("message")));
        }
        if (!(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("MCP License entitlement response has no data");
        }
        return data;
    }

    private String baseUrl() {
        String value = properties.getBaseUrl();
        return (value == null || value.isBlank() ? "http://localhost:8090" : value.trim()).replaceAll("/+$", "");
    }

    private int timeoutMs() {
        return properties.getTimeoutMs() <= 0 ? 5000 : properties.getTimeoutMs();
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(text(value));
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        return value instanceof Number number ? number.intValue() : Integer.valueOf(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

}
