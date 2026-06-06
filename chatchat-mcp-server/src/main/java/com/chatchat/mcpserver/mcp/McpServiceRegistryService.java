package com.chatchat.mcpserver.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class McpServiceRegistryService {

    private final McpServiceRegistrationRepository repository;
    private final McpTokenGenerator tokenGenerator;

    @Transactional(readOnly = true)
    public List<McpServiceRegistration> listAll() {
        return repository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public McpServiceRegistration getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("MCP service not found: " + id));
    }

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

    @Transactional
    public McpServiceRegistration create(McpServiceRegistration draft) {
        validate(draft);
        if (draft.getServiceToken() == null || draft.getServiceToken().isBlank()) {
            draft.setServiceToken(generateUniqueToken());
        }
        return repository.save(draft);
    }

    @Transactional
    public McpServiceRegistration update(String id, McpServiceRegistration draft) {
        McpServiceRegistration current = getById(id);
        current.setName(draft.getName());
        current.setEndpoint(draft.getEndpoint());
        current.setServiceToken(draft.getServiceToken());
        current.setServiceType(draft.getServiceType());
        current.setPermissionGroup(draft.getPermissionGroup());
        current.setEnabled(draft.isEnabled());
        current.setStatus(draft.getStatus());
        validate(current);
        return repository.save(current);
    }

    @Transactional
    public McpServiceRegistration setEnabled(String id, boolean enabled) {
        McpServiceRegistration current = getById(id);
        current.setEnabled(enabled);
        current.setStatus(enabled ? "ACTIVE" : "DISABLED");
        return repository.save(current);
    }

    @Transactional
    public McpServiceRegistration regenerateToken(String id) {
        McpServiceRegistration current = getById(id);
        current.setServiceToken(generateUniqueToken());
        return repository.save(current);
    }

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

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    public String generateUniqueToken() {
        String token;
        do {
            token = tokenGenerator.generate();
        } while (repository.existsByServiceToken(token));
        return token;
    }

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
        service.setStatus(defaultText(service.getStatus(), service.isEnabled() ? "ACTIVE" : "DISABLED")
            .toUpperCase(Locale.ROOT));
    }

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

    private String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }

    private String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
