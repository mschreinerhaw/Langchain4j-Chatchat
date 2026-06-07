package com.chatchat.mcpserver.api;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ApiServiceConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ApiServiceConfigRepository repository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ApiServiceConfig> listAll() {
        return repository.findAll().stream()
            .sorted((a, b) -> a.getToolName().compareToIgnoreCase(b.getToolName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ApiServiceConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByToolNameAsc();
    }

    @Transactional(readOnly = true)
    public ApiServiceConfig getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("API service config not found: " + id));
    }

    @Transactional
    public ApiServiceConfig create(ApiServiceConfig draft) {
        validate(draft, null);
        return repository.save(draft);
    }

    @Transactional
    public ApiServiceConfig upsertByToolName(ApiServiceConfig draft) {
        return repository.findByToolNameIgnoreCase(draft.getToolName())
            .map(existing -> update(existing.getId(), draft))
            .orElseGet(() -> create(draft));
    }

    @Transactional(readOnly = true)
    public boolean existsByToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return repository.findByToolNameIgnoreCase(toolName.trim()).isPresent();
    }

    @Transactional
    public ApiServiceConfig update(String id, ApiServiceConfig draft) {
        ApiServiceConfig current = getById(id);
        current.setToolName(draft.getToolName());
        current.setTitle(draft.getTitle());
        current.setDescription(draft.getDescription());
        current.setMethod(draft.getMethod());
        current.setUrlTemplate(draft.getUrlTemplate());
        current.setHeadersJson(draft.getHeadersJson());
        current.setBodyTemplate(draft.getBodyTemplate());
        current.setInputSchemaJson(draft.getInputSchemaJson());
        current.setEnabled(draft.isEnabled());
        current.setTimeoutMs(draft.getTimeoutMs());
        current.setCacheEnabled(draft.isCacheEnabled());
        current.setCacheTtlSeconds(draft.getCacheTtlSeconds());
        validate(current, id);
        return repository.save(current);
    }

    @Transactional
    public ApiServiceConfig setEnabled(String id, boolean enabled) {
        ApiServiceConfig current = getById(id);
        current.setEnabled(enabled);
        return repository.save(current);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    private void validate(ApiServiceConfig config, String currentId) {
        if (config.getToolName() == null || config.getToolName().isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        String toolName = config.getToolName().trim();
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName can only contain letters, numbers, '_' and '-'");
        }
        if (toolRegistry.hasTool(toolName)) {
            throw new IllegalArgumentException("toolName conflicts with built-in tool: " + toolName);
        }
        repository.findByToolNameIgnoreCase(toolName)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("toolName already exists: " + toolName);
            });
        config.setToolName(toolName);

        if (config.getTitle() == null || config.getTitle().isBlank()) {
            config.setTitle(toolName);
        } else {
            config.setTitle(config.getTitle().trim());
        }

        String method = config.getMethod() == null ? "GET" : config.getMethod().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("method must be one of " + ALLOWED_METHODS);
        }
        config.setMethod(method);

        if (config.getUrlTemplate() == null || config.getUrlTemplate().isBlank()) {
            throw new IllegalArgumentException("urlTemplate is required");
        }
        validateUrlTemplate(config.getUrlTemplate().trim());
        config.setUrlTemplate(config.getUrlTemplate().trim());

        config.setDescription(blankToNull(config.getDescription()));
        config.setHeadersJson(normalizeJsonObject(config.getHeadersJson(), "headers"));
        config.setInputSchemaJson(normalizeJsonObject(config.getInputSchemaJson(), "inputSchema"));
        config.setBodyTemplate(blankToNull(config.getBodyTemplate()));
        if (config.getTimeoutMs() <= 0) {
            config.setTimeoutMs(20000);
        }
        if (config.getCacheTtlSeconds() <= 0) {
            config.setCacheTtlSeconds(300);
        }
    }

    private void validateUrlTemplate(String urlTemplate) {
        String preview = urlTemplate.replaceAll("\\{\\{[^}]+}}", "x").replaceAll("\\{[^}]+}", "x");
        try {
            URI uri = URI.create(preview);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("urlTemplate must start with http:// or https://");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("urlTemplate is not a valid absolute URL template");
        }
    }

    private String normalizeJsonObject(String json, String fieldName) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(value, new TypeReference<>() {});
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object");
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
