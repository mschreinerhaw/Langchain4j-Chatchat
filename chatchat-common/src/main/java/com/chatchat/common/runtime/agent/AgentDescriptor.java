package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.capability.CapabilityId;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry metadata. Secrets are referenced, never embedded. */
public record AgentDescriptor(
    String agentId,
    String version,
    Origin origin,
    Protocol protocol,
    URI endpoint,
    Set<CapabilityId> capabilities,
    TrustLevel trustLevel,
    DataAccessMode dataAccessMode,
    Set<String> allowedDataDomains,
    Set<String> allowedEvidenceTypes,
    String outputSchema,
    String credentialRef,
    int priority,
    boolean enabled,
    Map<String, Object> metadata
) {
    public AgentDescriptor {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        agentId = agentId.trim();
        version = version == null || version.isBlank() ? "v1" : version.trim();
        origin = origin == null ? Origin.LOCAL : origin;
        protocol = protocol == null ? Protocol.LOCAL : protocol;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        trustLevel = trustLevel == null ? TrustLevel.UNTRUSTED : trustLevel;
        dataAccessMode = dataAccessMode == null ? DataAccessMode.RUNTIME_MANAGED : dataAccessMode;
        allowedDataDomains = clean(allowedDataDomains);
        allowedEvidenceTypes = clean(allowedEvidenceTypes);
        outputSchema = outputSchema == null || outputSchema.isBlank()
            ? AgentExecutionOutcome.SCHEMA_VERSION : outputSchema.trim();
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (origin != Origin.LOCAL && endpoint == null) {
            throw new IllegalArgumentException("remote agent endpoint is required");
        }
        if (origin != Origin.LOCAL && !secureEndpoint(endpoint)) {
            throw new IllegalArgumentException("remote agent endpoint must use HTTPS (HTTP is allowed only on loopback)");
        }
        if (origin != Origin.LOCAL && dataAccessMode != DataAccessMode.RUNTIME_MANAGED) {
            throw new IllegalArgumentException("remote agents must use RUNTIME_MANAGED data access");
        }
        metadata.keySet().forEach(key -> {
            String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.matches(".*(password|secret|api[_-]?key|auth[_-]?token|access[_-]?token|bearer).*")) {
                throw new IllegalArgumentException("agent metadata must not contain credentials; use credentialRef");
            }
        });
    }

    public boolean provides(CapabilityId capability) { return capabilities.contains(capability); }

    /** Legacy descriptors are inference-only until they explicitly opt into agentic execution. */
    public Set<AgentExecutionMode> supportedExecutionModes() {
        Object value = metadata.get("supportedExecutionModes");
        if (!(value instanceof Iterable<?> values)) return Set.of(AgentExecutionMode.DOMAIN_INFERENCE);
        java.util.LinkedHashSet<AgentExecutionMode> modes = new java.util.LinkedHashSet<>();
        values.forEach(item -> modes.add(AgentExecutionMode.parse(item)));
        return Set.copyOf(modes);
    }

    public boolean supportsExecutionMode(AgentExecutionMode mode) {
        try { return supportedExecutionModes().contains(mode); }
        catch (IllegalArgumentException invalidDeclaration) { return false; }
    }

    private static Set<String> clean(Set<String> values) {
        return values == null ? Set.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean secureEndpoint(URI endpoint) {
        if (endpoint.getUserInfo() != null) return false;
        if ("https".equalsIgnoreCase(endpoint.getScheme())) return true;
        if (!"http".equalsIgnoreCase(endpoint.getScheme())) return false;
        String host = endpoint.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    public enum Origin { LOCAL, GROUP, EXTERNAL }
    public enum Protocol { LOCAL, A2A_HTTP_JSON, HTTP_JSON, OPENAI_COMPATIBLE }
    public enum TrustLevel { INTERNAL, GROUP_TRUSTED, PARTNER, UNTRUSTED }
    public enum DataAccessMode { RUNTIME_MANAGED }
}
