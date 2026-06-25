package com.chatchat.mcpserver.ops;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.mcpserver.routing.AssetExecutionTargetBinding;
import com.chatchat.mcpserver.routing.ExecutionTargetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SshHostConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^ssh_[A-Za-z0-9_]{2,124}$");

    private final SshHostConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final ExecutionTargetService executionTargetService;

    public List<SshHostConfig> listAll() {
        return repository.findAll().stream()
            .sorted(Comparator.comparing(SshHostConfig::getName))
            .toList();
    }

    public List<SshHostConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByNameAsc();
    }

    @Transactional
    public SshHostConfig create(SshHostConfig config) {
        normalize(config, null);
        SshHostConfig saved = repository.save(config);
        syncExecutionTargets(saved, config.getExecutionTargets());
        return saved;
    }

    @Transactional
    public SshHostConfig update(String id, SshHostConfig request) {
        SshHostConfig config = getById(id);
        config.setName(firstText(request.getName(), config.getName()));
        config.setToolName(firstText(request.getToolName(), config.getToolName()));
        config.setTitle(firstText(request.getTitle(), config.getTitle()));
        config.setDescription(blankToNull(request.getDescription()));
        config.setHostname(firstText(request.getHostname(), config.getHostname()));
        config.setPort(request.getPort());
        config.setUsername(firstText(request.getUsername(), config.getUsername()));
        config.setAuthType(firstText(request.getAuthType(), config.getAuthType()));
        config.setPassword(request.getPassword());
        config.setPrivateKey(request.getPrivateKey());
        config.setPassphrase(request.getPassphrase());
        config.setHostKeyFingerprint(request.getHostKeyFingerprint());
        config.setEnabled(request.isEnabled());
        config.setEnvironment(firstText(request.getEnvironment(), config.getEnvironment()));
        config.setTags(request.getTags());
        config.setRoutingLabelsJson(request.getRoutingLabelsJson());
        config.setRoutingLabels(request.getRoutingLabels());
        config.setCapabilitiesJson(request.getCapabilitiesJson());
        config.setCapabilities(request.getCapabilities());
        config.setAllowedCommandsJson(normalizeJsonArray(request.getAllowedCommandsJson(), "allowedCommands"));
        config.setGovernanceJson(normalizeJsonObject(request.getGovernanceJson(), "governance"));
        config.setRuntimeAction("confirm_required");
        config.setConnectTimeoutMs(request.getConnectTimeoutMs());
        config.setCommandTimeoutMs(request.getCommandTimeoutMs());
        normalize(config, id);
        SshHostConfig saved = repository.save(config);
        syncExecutionTargets(saved, request.getExecutionTargets());
        return saved;
    }

    public SshHostConfig getEnabled(String id) {
        SshHostConfig config = getById(id);
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("SSH host is disabled: " + id);
        }
        return config;
    }

    public SshHostConfig getById(String id) {
        return repository.findById(requireText(id, "SSH host ID cannot be empty"))
            .orElseThrow(() -> new IllegalArgumentException("SSH host not found: " + id));
    }

    @Transactional
    public void delete(String id) {
        SshHostConfig config = getById(id);
        repository.delete(config);
    }

    private void normalize(SshHostConfig config, String currentId) {
        config.setName(firstText(config.getName(), config.getHostname()));
        assertUniqueName(config.getName(), currentId);
        config.setToolName(normalizeToolName(firstText(config.getToolName(), defaultToolName(config))));
        repository.findByToolNameIgnoreCase(config.getToolName())
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("SSH host toolName already exists: " + config.getToolName());
            });
        config.setTitle(firstText(config.getTitle(), config.getName()));
        config.setDescription(firstText(config.getDescription(),
            "用途：通过 SSH 巡检 " + config.getName() + "，只能执行 Runtime 注册的命令模板，禁止自由 Shell 命令。"));
        config.setHostname(requireText(config.getHostname(), "SSH host cannot be empty"));
        config.setPort(config.getPort() <= 0 ? 22 : Math.min(config.getPort(), 65535));
        config.setUsername(requireText(config.getUsername(), "SSH username cannot be empty"));
        config.setAuthType(normalizeAuthType(config.getAuthType()));
        config.setEnvironment(normalizeEnvironment(config.getEnvironment()));
        config.setRuntimeAction("confirm_required");
        config.setRoutingLabelsJson(normalizeStringArray(mergedProtocolValues(config.getRoutingLabelsJson(), config.getRoutingLabels()), "routingLabels"));
        config.setCapabilitiesJson(firstText(
            normalizeStringArray(mergedProtocolValues(config.getCapabilitiesJson(), config.getCapabilities()), "capabilities"),
            ModelProtocolJson.compact(List.of("ssh", "linux_command_execute"))
        ));
        config.setTags(mergeTags(config.getTags(), config.getRoutingLabelsJson(), config.getCapabilitiesJson()));
        config.setAllowedCommandsJson(normalizeJsonArray(config.getAllowedCommandsJson(), "allowedCommands"));
        config.setGovernanceJson(normalizeJsonObject(config.getGovernanceJson(), "governance"));
        config.setConnectTimeoutMs(Math.max(1000, config.getConnectTimeoutMs()));
        config.setCommandTimeoutMs(Math.max(1000, config.getCommandTimeoutMs()));
    }

    private void assertUniqueName(String name, String currentId) {
        String normalized = requireText(name, "SSH host name cannot be empty");
        repository.findByNameIgnoreCase(normalized)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("SSH host name already exists: " + normalized);
            });
    }

    private void syncExecutionTargets(SshHostConfig asset, List<AssetExecutionTargetBinding> bindings) {
        if (asset == null || bindings == null || bindings.isEmpty()) {
            return;
        }
        for (AssetExecutionTargetBinding binding : bindings) {
            if (binding == null || binding.targetKey() == null || binding.targetKey().isBlank()) {
                continue;
            }
            executionTargetService.upsertAssetBinding(
                binding,
                ExecutionTargetService.ASSET_TYPE_SSH_HOST,
                asset.getEnvironment(),
                "TOOL_NAME",
                asset.getToolName()
            );
        }
    }

    private String normalizeToolName(String value) {
        String toolName = requireText(value, "toolName cannot be empty").toLowerCase(Locale.ROOT);
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("SSH host toolName must follow ssh_{assetName}");
        }
        return toolName;
    }

    private String defaultToolName(SshHostConfig config) {
        String base = firstText(config.getName(), firstText(config.getHostname(), "host"))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (base.isBlank()) {
            base = "host";
        }
        return "ssh_" + base;
    }

    private String normalizeAuthType(String value) {
        String normalized = firstText(value, "PASSWORD").toUpperCase(Locale.ROOT);
        return "PRIVATE_KEY".equals(normalized) ? "PRIVATE_KEY" : "PASSWORD";
    }

    private String normalizeEnvironment(String value) {
        String normalized = firstText(value, "DEV").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "TEST", "UAT", "PROD" -> normalized;
            default -> "DEV";
        };
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

    private String normalizeJsonArray(String json, String field) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            List<String> items = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .toList());
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON string array");
        }
    }

    private String normalizeStringArray(String json, String field) {
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

    private String normalizeJsonObject(String json, String field) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?> map)) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
            return ModelProtocolJson.compact(map);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
    }
}
