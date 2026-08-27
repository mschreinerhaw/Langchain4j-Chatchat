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

    private final McpCenterProperties properties;
    private final InternalCredentialProperties credentials;
    private final WebClient webClient = WebClient.builder().build();

    @Override
    public AgentPublicationLimit agentPublicationLimit() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = InternalRequestSigner.sign(credentials.resolvedSecret(), "GET", AGENT_LIMIT_PATH,
            timestamp, nonce);
        Object raw = webClient.get()
            .uri(baseUrl() + AGENT_LIMIT_PATH)
            .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
            .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
            .header(InternalRequestSigner.NONCE_HEADER, nonce)
            .header(InternalRequestSigner.SIGNATURE_HEADER, signature)
            .retrieve()
            .bodyToMono(Object.class)
            .timeout(Duration.ofMillis(Math.max(1000, timeoutMs())))
            .block();
        Map<?, ?> data = unwrapData(raw);
        return new AgentPublicationLimit(
            booleanValue(data.get("licenseValid")),
            text(data.get("licenseStatus")),
            text(data.get("message")),
            integer(data.get("maxPublishedAgents")),
            booleanValue(data.get("limited"))
        );
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
