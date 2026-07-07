package com.chatchat.mcpserver.ops;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class HttpEndpointConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^http_[A-Za-z0-9_]{2,123}$");
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE");

    private final HttpEndpointConfigRepository repository;
    private final ObjectMapper objectMapper;

    public List<HttpEndpointConfig> listAll() {
        return repository.findAll().stream()
            .sorted(Comparator.comparing(HttpEndpointConfig::getName))
            .toList();
    }

    public List<HttpEndpointConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByNameAsc();
    }

    public java.util.Optional<HttpEndpointConfig> findByToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return java.util.Optional.empty();
        }
        return repository.findByToolNameIgnoreCase(toolName.trim());
    }

    @Transactional
    public HttpEndpointConfig create(HttpEndpointConfig config) {
        normalize(config, null);
        return repository.save(config);
    }

    @Transactional
    public HttpEndpointConfig upsertByToolName(HttpEndpointConfig request) {
        return findByToolName(request.getToolName())
            .map(existing -> update(existing.getId(), request))
            .orElseGet(() -> create(request));
    }

    @Transactional
    public HttpEndpointConfig update(String id, HttpEndpointConfig request) {
        HttpEndpointConfig config = getById(id);
        config.setName(firstText(request.getName(), config.getName()));
        config.setToolName(firstText(request.getToolName(), config.getToolName()));
        config.setTitle(firstText(request.getTitle(), config.getTitle()));
        config.setDescription(blankToNull(request.getDescription()));
        config.setMethod(firstText(request.getMethod(), config.getMethod()));
        config.setUrlTemplate(firstText(request.getUrlTemplate(), config.getUrlTemplate()));
        config.setHeadersJson(normalizeJsonObject(request.getHeadersJson(), "headers"));
        config.setBodyTemplate(blankToNull(request.getBodyTemplate()));
        config.setInputSchemaJson(normalizeJsonObject(request.getInputSchemaJson(), "inputSchema"));
        config.setGovernanceJson(normalizeJsonObject(request.getGovernanceJson(), "governance"));
        config.setEnabled(request.isEnabled());
        config.setEnvironment(firstText(request.getEnvironment(), config.getEnvironment()));
        config.setCategory(firstText(request.getCategory(), config.getCategory()));
        config.setTags(request.getTags());
        config.setRoutingLabelsJson(request.getRoutingLabelsJson());
        config.setRoutingLabels(request.getRoutingLabels());
        config.setCapabilitiesJson(request.getCapabilitiesJson());
        config.setCapabilities(request.getCapabilities());
        config.setRuntimeAction("readonly");
        config.setTimeoutMs(request.getTimeoutMs());
        normalize(config, id);
        return repository.save(config);
    }

    public HttpEndpointConfig getById(String id) {
        return repository.findById(requireText(id, "HTTP endpoint ID cannot be empty"))
            .orElseThrow(() -> new IllegalArgumentException("HTTP endpoint not found: " + id));
    }

    @Transactional
    public void delete(String id) {
        HttpEndpointConfig config = getById(id);
        repository.delete(config);
    }

    private void normalize(HttpEndpointConfig config, String currentId) {
        config.setName(firstText(config.getName(), config.getToolName()));
        assertUniqueName(config.getName(), currentId);
        config.setToolName(normalizeToolName(firstText(config.getToolName(), defaultToolName(config))));
        repository.findByToolNameIgnoreCase(config.getToolName())
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("HTTP endpoint toolName already exists: " + config.getToolName());
            });
        config.setTitle(firstText(config.getTitle(), config.getName()));
        config.setDescription(firstText(config.getDescription(), "用途：调用 " + config.getName() + " HTTP 接口。"));
        config.setMethod(normalizeMethod(config.getMethod()));
        config.setUrlTemplate(requireHttpUrl(config.getUrlTemplate()));
        config.setHeadersJson(normalizeJsonObject(config.getHeadersJson(), "headers"));
        config.setInputSchemaJson(normalizeJsonObject(config.getInputSchemaJson(), "inputSchema"));
        config.setGovernanceJson(normalizeJsonObject(config.getGovernanceJson(), "governance"));
        config.setEnvironment(normalizeEnvironment(config.getEnvironment()));
        config.setCategory(firstText(config.getCategory(), "api_gateway").toLowerCase(Locale.ROOT));
        config.setRoutingLabelsJson(normalizeJsonArray(mergedProtocolValues(config.getRoutingLabelsJson(), config.getRoutingLabels()), "routingLabels"));
        config.setCapabilitiesJson(firstText(
            normalizeJsonArray(mergedProtocolValues(config.getCapabilitiesJson(), config.getCapabilities()), "capabilities"),
            ModelProtocolJson.compact(List.of("http", "http_request"))
        ));
        config.setTags(mergeTags(config.getTags(), config.getRoutingLabelsJson(), config.getCapabilitiesJson()));
        config.setRuntimeAction("readonly");
        config.setTimeoutMs(Math.max(1000, Math.min(config.getTimeoutMs(), 60000)));
    }

    private void assertUniqueName(String name, String currentId) {
        String normalized = requireText(name, "HTTP endpoint name cannot be empty");
        repository.findByNameIgnoreCase(normalized)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("HTTP endpoint name already exists: " + normalized);
            });
    }

    private String normalizeToolName(String value) {
        String toolName = requireText(value, "toolName cannot be empty").toLowerCase(Locale.ROOT);
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("HTTP endpoint toolName must follow http_{assetName}");
        }
        if ("http_request".equals(toolName) || "http_request_execute".equals(toolName)
            || "linux_command_execute".equals(toolName)) {
            throw new IllegalArgumentException("HTTP endpoint toolName conflicts with built-in tool: " + toolName);
        }
        return toolName;
    }

    private String defaultToolName(HttpEndpointConfig config) {
        String base = firstText(config.getName(), "request")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (base.isBlank()) {
            base = "request";
        }
        return "http_" + base;
    }

    private String normalizeMethod(String value) {
        String method = firstText(value, "GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("HTTP method must be GET, POST, PUT or DELETE");
        }
        return method;
    }

    private String normalizeEnvironment(String value) {
        String normalized = firstText(value, "DEV").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "TEST", "UAT", "PROD" -> normalized;
            default -> "DEV";
        };
    }

    private String requireHttpUrl(String value) {
        String url = requireText(value, "urlTemplate cannot be empty");
        String probe = url.replaceAll("\\{\\{[^}]+}}", "x").replaceAll("\\{[^}]+}", "x");
        try {
            URI uri = URI.create(probe);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("urlTemplate must start with http:// or https://");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("urlTemplate must be a valid HTTP URL");
        }
        return url;
    }

    private String normalizeJsonObject(String json, String field) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(parsed);
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
    }

    private String normalizeJsonArray(String json, String field) {
        String value = blankToNull(json);
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

    private String mergeTags(String tags, String routingLabelsJson, String capabilitiesJson) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (tags != null && !tags.isBlank()) {
            for (String item : tags.split("[,;\\s]+")) {
                if (!item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        readJsonArray(routingLabelsJson).forEach(merged::add);
        readJsonArray(capabilitiesJson).forEach(merged::add);
        return merged.isEmpty() ? null : String.join(",", merged);
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

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
