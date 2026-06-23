package com.chatchat.mcpserver.routing;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExecutionTargetService {

    public static final String ASSET_TYPE_SSH_HOST = "SSH_HOST";
    public static final String ASSET_TYPE_SQL_DATASOURCE = "SQL_DATASOURCE";
    public static final String ASSET_TYPE_HTTP_ENDPOINT = "HTTP_ENDPOINT";
    public static final String ASSET_TYPE_DATABASE_QUERY = "DATABASE_QUERY";

    private static final Pattern TARGET_KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.:-]{1,127}$");

    private final ExecutionTargetConfigRepository repository;
    private final ObjectMapper objectMapper;

    public List<ExecutionTargetConfig> listAll() {
        return repository.findAll().stream()
            .sorted(Comparator
                .comparingInt(ExecutionTargetConfig::getPriority)
                .thenComparing(ExecutionTargetConfig::getTargetKey))
            .toList();
    }

    public List<ExecutionTargetConfig> listEnabledByAssetType(String assetType) {
        String normalized = normalizeAssetType(assetType);
        return repository.findByAssetTypeAndEnabledTrueOrderByPriorityAscTargetKeyAsc(normalized);
    }

    @Transactional
    public ExecutionTargetConfig create(ExecutionTargetConfig config) {
        normalize(config, null);
        return repository.save(config);
    }

    @Transactional
    public ExecutionTargetConfig update(String id, ExecutionTargetConfig request) {
        ExecutionTargetConfig config = getById(id);
        config.setTargetKey(firstText(request.getTargetKey(), config.getTargetKey()));
        config.setName(firstText(request.getName(), config.getName()));
        config.setDescription(blankToNull(request.getDescription()));
        config.setAssetType(firstText(request.getAssetType(), config.getAssetType()));
        config.setEnvironment(blankToNull(request.getEnvironment()));
        config.setSelectorType(firstText(request.getSelectorType(), config.getSelectorType()));
        config.setSelectorValue(firstText(request.getSelectorValue(), config.getSelectorValue()));
        config.setLabelsJson(request.getLabelsJson());
        config.setEnabled(request.isEnabled());
        config.setPriority(request.getPriority());
        normalize(config, id);
        return repository.save(config);
    }

    @Transactional
    public ExecutionTargetConfig upsertAssetBinding(AssetExecutionTargetBinding binding,
                                                    String assetType,
                                                    String assetEnvironment,
                                                    String defaultSelectorType,
                                                    String defaultSelectorValue) {
        if (binding == null) {
            throw new IllegalArgumentException("execution target binding is required");
        }
        String key = requireText(binding.targetKey(), "targetKey is required")
            .trim()
            .toLowerCase(Locale.ROOT);
        ExecutionTargetConfig config = repository.findByTargetKeyIgnoreCase(key)
            .orElseGet(ExecutionTargetConfig::new);
        config.setTargetKey(key);
        config.setName(firstText(binding.name(), key));
        config.setDescription(blankToNull(binding.description()));
        config.setAssetType(firstText(assetType, config.getAssetType()));
        config.setEnvironment(firstText(binding.environment(), assetEnvironment));
        config.setSelectorType(firstText(binding.selectorType(), defaultSelectorType));
        config.setSelectorValue(firstText(binding.selectorValue(), defaultSelectorValue));
        config.setLabelsJson(writeLabelsJson(binding.labels()));
        config.setEnabled(binding.enabled() == null || binding.enabled());
        config.setPriority(binding.priority() == null ? 100 : binding.priority());
        normalize(config, config.getId());
        return repository.save(config);
    }

    public ExecutionTargetConfig getById(String id) {
        return repository.findById(requireText(id, "Execution target ID is required"))
            .orElseThrow(() -> new IllegalArgumentException("Execution target not found: " + id));
    }

    @Transactional
    public void delete(String id) {
        repository.delete(getById(id));
    }

    private void normalize(ExecutionTargetConfig config, String currentId) {
        String key = requireText(config.getTargetKey(), "targetKey is required")
            .trim()
            .toLowerCase(Locale.ROOT);
        if (!TARGET_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("targetKey must use lowercase letters, numbers, dot, colon, underscore or hyphen");
        }
        repository.findByTargetKeyIgnoreCase(key)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("Execution target key already exists: " + key);
            });
        config.setTargetKey(key);
        config.setName(firstText(config.getName(), key));
        config.setAssetType(normalizeAssetType(config.getAssetType()));
        config.setEnvironment(normalizeEnvironment(config.getEnvironment()));
        config.setSelectorType(normalizeSelectorType(config.getSelectorType()));
        config.setSelectorValue(requireText(config.getSelectorValue(), "selectorValue is required"));
        config.setLabelsJson(normalizeLabelsJson(config.getLabelsJson()));
        config.setPriority(config.getPriority() <= 0 ? 100 : config.getPriority());
    }

    private String normalizeAssetType(String value) {
        String normalized = firstText(value, ASSET_TYPE_SSH_HOST).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ASSET_TYPE_SSH_HOST, "HOST", "SSH" -> ASSET_TYPE_SSH_HOST;
            case ASSET_TYPE_SQL_DATASOURCE, "SQL", "DATABASE", "DATASOURCE" -> ASSET_TYPE_SQL_DATASOURCE;
            case ASSET_TYPE_HTTP_ENDPOINT, "HTTP", "ENDPOINT", "API" -> ASSET_TYPE_HTTP_ENDPOINT;
            case ASSET_TYPE_DATABASE_QUERY, "QUERY", "DB_QUERY" -> ASSET_TYPE_DATABASE_QUERY;
            default -> throw new IllegalArgumentException("assetType must be SSH_HOST, SQL_DATASOURCE, HTTP_ENDPOINT or DATABASE_QUERY");
        };
    }

    private String normalizeSelectorType(String value) {
        String normalized = firstText(value, "LABEL").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LABEL", "NAME", "TOOL_NAME" -> normalized;
            default -> throw new IllegalArgumentException("selectorType must be LABEL, NAME or TOOL_NAME");
        };
    }

    private String normalizeEnvironment(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "TEST", "UAT", "PROD" -> normalized;
            default -> throw new IllegalArgumentException("environment must be DEV, TEST, UAT or PROD");
        };
    }

    private String normalizeLabelsJson(String json) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            List<String> items = objectMapper.readValue(value, new TypeReference<>() {});
            List<String> normalized = items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
            return normalized.isEmpty() ? null : ModelProtocolJson.compact(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("labelsJson must be a JSON string array");
        }
    }

    private String writeLabelsJson(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        List<String> normalized = labels.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(item -> item.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        return normalized.isEmpty() ? null : ModelProtocolJson.compact(normalized);
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
