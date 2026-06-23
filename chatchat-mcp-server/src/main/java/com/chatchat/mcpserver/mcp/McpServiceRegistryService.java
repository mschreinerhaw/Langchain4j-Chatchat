package com.chatchat.mcpserver.mcp;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class McpServiceRegistryService {

    private final McpServiceRegistrationRepository repository;
    private final McpTokenGenerator tokenGenerator;
    private final ObjectMapper objectMapper;

    /**
     * Lists the all.
     *
     * @return the all list
     */
    @Transactional(readOnly = true)
    public List<McpServiceRegistration> listAll() {
        return repository.findAllByOrderByNameAsc();
    }

    /**
     * Returns the by id.
     *
     * @param id the id value
     * @return the by id
     */
    @Transactional(readOnly = true)
    public McpServiceRegistration getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("MCP service not found: " + id));
    }

    /**
     * Returns whether is valid token.
     *
     * @param token the token value
     * @return whether the condition is satisfied
     */
    @Transactional(readOnly = true)
    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return repository.findByServiceToken(token.trim())
            .filter(McpServiceRegistration::isEnabled)
            .filter(service -> "ACTIVE".equalsIgnoreCase(service.getStatus()))
            .isPresent();
    }

    /**
     * Creates the create.
     *
     * @param draft the draft value
     * @return the created create
     */
    @Transactional
    public McpServiceRegistration create(McpServiceRegistration draft) {
        validate(draft);
        if (draft.getServiceToken() == null || draft.getServiceToken().isBlank()) {
            draft.setServiceToken(generateUniqueToken());
        }
        return repository.save(draft);
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param draft the draft value
     * @return the updated update
     */
    @Transactional
    public McpServiceRegistration update(String id, McpServiceRegistration draft) {
        McpServiceRegistration current = getById(id);
        current.setName(draft.getName());
        current.setEndpoint(draft.getEndpoint());
        current.setServiceToken(draft.getServiceToken());
        current.setServiceType(draft.getServiceType());
        current.setPermissionGroup(draft.getPermissionGroup());
        current.setEnvironment(draft.getEnvironment());
        current.setRoutingLabelsJson(draft.getRoutingLabelsJson());
        current.setRoutingLabels(draft.getRoutingLabels());
        current.setCapabilitiesJson(draft.getCapabilitiesJson());
        current.setCapabilities(draft.getCapabilities());
        current.setEnabled(draft.isEnabled());
        current.setStatus(draft.getStatus());
        validate(current);
        return repository.save(current);
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @Transactional
    public McpServiceRegistration setEnabled(String id, boolean enabled) {
        McpServiceRegistration current = getById(id);
        current.setEnabled(enabled);
        current.setStatus(enabled ? "ACTIVE" : "DISABLED");
        return repository.save(current);
    }

    /**
     * Performs the regenerate token operation.
     *
     * @param id the id value
     * @return the operation result
     */
    @Transactional
    public McpServiceRegistration regenerateToken(String id) {
        McpServiceRegistration current = getById(id);
        current.setServiceToken(generateUniqueToken());
        return repository.save(current);
    }

    /**
     * Performs the heartbeat operation.
     *
     * @param token the token value
     * @return the operation result
     */
    @Transactional
    public McpServiceRegistration heartbeat(String token) {
        McpServiceRegistration current = repository.findByServiceToken(token)
            .orElseThrow(() -> new IllegalArgumentException("Invalid MCP token"));
        if (!current.isEnabled()) {
            throw new IllegalArgumentException("MCP service is disabled");
        }
        current.setStatus("ACTIVE");
        current.setLastHeartbeatAt(Instant.now());
        return repository.save(current);
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     */
    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * Performs the generate unique token operation.
     *
     * @return the operation result
     */
    public String generateUniqueToken() {
        String token;
        do {
            token = tokenGenerator.generate();
        } while (repository.existsByServiceToken(token));
        return token;
    }

    /**
     * Validates the validate.
     *
     * @param service the service value
     */
    private void validate(McpServiceRegistration service) {
        if (service.getName() == null || service.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        service.setName(service.getName().trim());
        if (service.getEndpoint() == null || service.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        validateEndpoint(service.getEndpoint().trim());
        service.setEndpoint(service.getEndpoint().trim());
        service.setServiceToken(normalizeToken(service.getServiceToken()));
        service.setServiceType(defaultText(service.getServiceType(), "DATA").toUpperCase(Locale.ROOT));
        service.setPermissionGroup(defaultText(service.getPermissionGroup(), "default"));
        service.setEnvironment(normalizeEnvironment(service.getEnvironment()));
        service.setRoutingLabelsJson(normalizeStringArray(
            mergedProtocolValues(service.getRoutingLabelsJson(), service.getRoutingLabels()),
            "routingLabels"
        ));
        service.setCapabilitiesJson(normalizeStringArray(
            mergedProtocolValues(service.getCapabilitiesJson(), service.getCapabilities()),
            "capabilities"
        ));
        service.setStatus(defaultText(service.getStatus(), service.isEnabled() ? "ACTIVE" : "DISABLED")
            .toUpperCase(Locale.ROOT));
    }

    private String normalizeEnvironment(String value) {
        String normalized = defaultText(value, "DEV").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "TEST", "UAT", "PROD" -> normalized;
            default -> "DEV";
        };
    }

    private String normalizeStringArray(String json, String field) {
        String value = json == null || json.isBlank() ? null : json.trim();
        if (value == null) {
            return null;
        }
        try {
            List<String> items = objectMapper.readValue(value, new TypeReference<>() {});
            List<String> normalized = items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
            return normalized.isEmpty() ? null : ModelProtocolJson.compact(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON string array");
        }
    }

    private String mergedProtocolValues(String json, List<String> values) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        readJsonArray(json).forEach(merged::add);
        if (values != null) {
            values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.isEmpty() ? null : ModelProtocolJson.compact(new ArrayList<>(merged));
    }

    private List<String> readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * Validates the endpoint.
     *
     * @param endpoint the endpoint value
     */
    private void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("endpoint must start with http:// or https://");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("endpoint is not a valid absolute URL");
        }
    }

    /**
     * Normalizes the token.
     *
     * @param token the token value
     * @return the operation result
     */
    private String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }

    /**
     * Performs the default text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
