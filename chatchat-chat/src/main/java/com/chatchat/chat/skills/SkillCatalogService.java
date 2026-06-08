package com.chatchat.chat.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persistent skill catalog for enterprise scenarios.
 */
@Service
@RequiredArgsConstructor
public class SkillCatalogService {

    private static final String DEFAULT_SKILL_ID = "general";
    public static final String MARKET_STATUS_DRAFT = "draft";
    public static final String MARKET_STATUS_PUBLISHED = "published";
    public static final String MARKET_STATUS_RECALLED = "recalled";
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{2,64}$");
    private static final List<String> CUSTOM_EDITABLE_FIELDS = List.of(
        "label",
        "description",
        "usageScenarios",
        "skillTags",
        "defaultMode",
        "systemPrompt",
        "firstUseGreeting",
        "preferredToolPrefixes",
        "boundMcpServiceIds",
        "boundMcpToolNames",
        "toolConfigs",
        "routingSettings",
        "quickQuestions"
    );

    private final SkillConfigRepository repository;
    private final SkillConfigVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    @Transactional
    public void initializeDefaults() {
        ensureSkillSchemaCompatibility();
    }

    @Transactional(readOnly = true)
    public synchronized List<SkillDefinition> list() {
        return repository.findAll().stream()
            .map(this::toDefinition)
            .sorted(Comparator.comparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Transactional(readOnly = true)
    public synchronized SkillDefinition resolve(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return findGeneralOrDefault();
        }
        String id = skillId.trim().toLowerCase(Locale.ROOT);
        return repository.findById(id)
            .map(this::toDefinition)
            .orElseGet(this::findGeneralOrDefault);
    }

    @Transactional(readOnly = true)
    public synchronized boolean isBuiltinSkill(String skillId) {
        return false;
    }

    @Transactional(readOnly = true)
    public synchronized List<String> editableFields(String skillId) {
        return CUSTOM_EDITABLE_FIELDS;
    }

    @Transactional(readOnly = true)
    public synchronized List<String> resolveTools(String skillId, Collection<String> allTools) {
        return resolveTools(skillId, allTools, Map.of());
    }

    @Transactional(readOnly = true)
    public synchronized List<String> resolveTools(String skillId,
                                                  Collection<String> allTools,
                                                  Map<String, List<String>> mcpToolsByServiceId) {
        SkillDefinition skill = resolve(skillId);
        if (allTools == null || allTools.isEmpty()) {
            return List.of();
        }

        List<String> sortedAllTools = allTools.stream()
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .sorted()
            .toList();
        List<String> prefixes = normalizeList(skill.preferredToolPrefixes());
        List<String> boundMcpServiceIds = normalizeList(skill.boundMcpServiceIds());
        List<String> boundMcpToolNames = normalizeList(skill.boundMcpToolNames());
        List<SkillToolConfig> enabledToolConfigs = normalizeToolConfigs(skill.toolConfigs()).stream()
            .filter(config -> Boolean.TRUE.equals(config.enabled()))
            .toList();

        if ((boundMcpServiceIds.isEmpty() && boundMcpToolNames.isEmpty() && enabledToolConfigs.isEmpty())
            || mcpToolsByServiceId == null
            || mcpToolsByServiceId.isEmpty()) {
            return withDocumentWorkflowTool(applyPrefixSelection(sortedAllTools, prefixes), sortedAllTools, skill);
        }

        Set<String> registeredMcpTools = new LinkedHashSet<>();
        mcpToolsByServiceId.values().forEach(toolNames -> {
            if (toolNames != null) {
                registeredMcpTools.addAll(toolNames);
            }
        });

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<String> nonMcpTools = sortedAllTools.stream()
            .filter(toolName -> !registeredMcpTools.contains(toolName))
            .toList();
        if (!prefixes.isEmpty()) {
            selected.addAll(applyPrefixSelection(nonMcpTools, prefixes));
        }

        boundMcpServiceIds.stream()
            .map(mcpToolsByServiceId::get)
            .filter(toolNames -> toolNames != null && !toolNames.isEmpty())
            .flatMap(Collection::stream)
            .sorted()
            .forEach(selected::add);

        boundMcpToolNames.stream()
            .filter(registeredMcpTools::contains)
            .sorted()
            .forEach(selected::add);

        enabledToolConfigs.stream()
            .filter(config -> config.toolName() != null && !config.toolName().isBlank())
            .filter(config -> registeredMcpTools.contains(config.toolName()))
            .sorted(Comparator
                .comparingInt((SkillToolConfig config) -> config.callWeight() == null ? 5 : config.callWeight())
                .reversed()
                .thenComparing(config -> config.toolName().toLowerCase(Locale.ROOT)))
            .map(SkillToolConfig::toolName)
            .forEach(selected::add);

        return withDocumentWorkflowTool(List.copyOf(selected), sortedAllTools, skill);
    }

    @Transactional
    public synchronized SkillDefinition upsert(SkillDefinition draft) {
        if (draft == null) {
            throw new IllegalArgumentException("skill payload is required");
        }

        String id = normalizeId(draft.id());
        String label = normalizeText(draft.label());
        if (label == null) {
            throw new IllegalArgumentException("skill label is required");
        }

        String defaultMode = normalizeText(draft.defaultMode());
        if (defaultMode == null) {
            defaultMode = "agent_chat";
        }

        List<SkillToolConfig> toolConfigs = normalizeToolConfigs(draft.toolConfigs());
        List<String> boundMcpToolNames = !toolConfigs.isEmpty()
            ? toolConfigs.stream()
                .filter(config -> Boolean.TRUE.equals(config.enabled()))
                .map(SkillToolConfig::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList()
            : normalizeList(draft.boundMcpToolNames());

        SkillConfigEntity existing = repository.findById(id).orElse(null);
        String marketStatus = normalizeMarketStatus(draft.marketStatus());
        if (marketStatus == null) {
            marketStatus = existing == null
                ? defaultMarketStatus(id)
                : normalizeMarketStatus(existing.getMarketStatus());
        }

        SkillDefinition normalized = new SkillDefinition(
            id,
            label,
            normalizeText(draft.description()),
            normalizeList(draft.usageScenarios()),
            normalizeList(draft.skillTags()),
            defaultMode,
            normalizeText(draft.modelName()),
            normalizeText(draft.systemPrompt()),
            normalizeText(draft.firstUseGreeting()),
            normalizeList(draft.preferredToolPrefixes()),
            normalizeList(draft.boundMcpServiceIds()),
            boundMcpToolNames,
            normalizeList(draft.boundDocumentIds()),
            normalizeList(draft.boundDocumentTags()),
            toolConfigs,
            normalizeRoutingSettings(draft.routingSettings()),
            normalizeList(draft.quickQuestions()),
            marketStatus
        );

        SkillConfigEntity entity = existing == null ? new SkillConfigEntity() : existing;
        boolean exists = existing != null;
        entity.setId(id);
        entity.setLabel(normalized.label());
        entity.setDescription(normalized.description());
        entity.setUsageScenariosJson(writeListJson(normalized.usageScenarios()));
        entity.setSkillTagsJson(writeListJson(normalized.skillTags()));
        entity.setDefaultMode(normalized.defaultMode());
        entity.setModelName(normalized.modelName());
        entity.setFirstUseGreeting(normalized.firstUseGreeting());
        entity.setSystemPrompt(normalized.systemPrompt());
        entity.setPreferredToolPrefixesJson(writeListJson(normalized.preferredToolPrefixes()));
        entity.setBoundMcpServiceIdsJson(writeListJson(normalized.boundMcpServiceIds()));
        entity.setBoundMcpToolNamesJson(writeListJson(normalized.boundMcpToolNames()));
        entity.setBoundDocumentIdsJson(writeListJson(normalized.boundDocumentIds()));
        entity.setBoundDocumentTagsJson(writeListJson(normalized.boundDocumentTags()));
        entity.setToolConfigsJson(writeToolConfigsJson(normalized.toolConfigs()));
        entity.setRoutingSettingsJson(writeRoutingSettingsJson(normalized.routingSettings()));
        entity.setQuickQuestionsJson(writeListJson(normalized.quickQuestions()));
        entity.setMarketStatus(normalized.marketStatus());

        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, exists ? "update" : "create");
        return toDefinition(saved);
    }

    @Transactional(readOnly = true)
    public synchronized List<SkillVersionSnapshot> listVersions(String skillId) {
        String id = normalizeId(skillId);
        return versionRepository.findTop30BySkillIdOrderByCreatedAtDesc(id).stream()
            .map(this::toSkillVersionSnapshot)
            .toList();
    }

    @Transactional
    public synchronized SkillDefinition rollbackToVersion(String skillId, String versionId) {
        String id = normalizeId(skillId);
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId is required");
        }
        SkillConfigEntity current = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("skill not found: " + id));
        SkillConfigVersionEntity target = versionRepository.findById(versionId.trim())
            .orElseThrow(() -> new IllegalArgumentException("skill version not found: " + versionId));
        if (!id.equals(target.getSkillId())) {
            throw new IllegalArgumentException("version does not belong to skill: " + id);
        }

        snapshotVersion(current, "before_rollback");
        current.setLabel(target.getLabel());
        current.setDescription(target.getDescription());
        current.setUsageScenariosJson(target.getUsageScenariosJson());
        current.setSkillTagsJson(target.getSkillTagsJson());
        current.setDefaultMode(target.getDefaultMode());
        current.setModelName(target.getModelName());
        current.setSystemPrompt(target.getSystemPrompt());
        current.setFirstUseGreeting(target.getFirstUseGreeting());
        current.setPreferredToolPrefixesJson(target.getPreferredToolPrefixesJson());
        current.setBoundMcpServiceIdsJson(target.getBoundMcpServiceIdsJson());
        current.setBoundMcpToolNamesJson(target.getBoundMcpToolNamesJson());
        current.setBoundDocumentIdsJson(target.getBoundDocumentIdsJson());
        current.setBoundDocumentTagsJson(target.getBoundDocumentTagsJson());
        current.setToolConfigsJson(target.getToolConfigsJson());
        current.setRoutingSettingsJson(target.getRoutingSettingsJson());
        current.setQuickQuestionsJson(target.getQuickQuestionsJson());
        current.setMarketStatus(normalizeMarketStatus(target.getMarketStatus()) == null
            ? defaultMarketStatus(id)
            : normalizeMarketStatus(target.getMarketStatus()));
        SkillConfigEntity saved = repository.save(current);
        snapshotVersion(saved, "rollback");
        return toDefinition(saved);
    }

    @Transactional
    public synchronized SkillDefinition publishToMarket(String skillId) {
        return setMarketStatus(skillId, MARKET_STATUS_PUBLISHED, "publish");
    }

    @Transactional
    public synchronized SkillDefinition recallFromMarket(String skillId) {
        return setMarketStatus(skillId, MARKET_STATUS_RECALLED, "recall");
    }

    @Transactional
    public synchronized SkillDefinition setMarketStatus(String skillId, String status, String action) {
        String id = normalizeId(skillId);
        SkillConfigEntity entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("skill not found: " + id));
        String normalizedStatus = normalizeMarketStatus(status);
        if (normalizedStatus == null) {
            throw new IllegalArgumentException("market status is required");
        }
        entity.setMarketStatus(normalizedStatus);
        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, action == null || action.isBlank() ? "market_status" : action);
        return toDefinition(saved);
    }

    @Transactional
    public synchronized boolean delete(String skillId) {
        String id = normalizeId(skillId);
        boolean exists = repository.existsById(id);
        if (exists) {
            repository.deleteById(id);
        }
        return exists;
    }

    private SkillDefinition toDefinition(SkillConfigEntity entity) {
        return new SkillDefinition(
            entity.getId(),
            normalizeText(entity.getLabel()),
            normalizeText(entity.getDescription()),
            readListJson(entity.getUsageScenariosJson()),
            readListJson(entity.getSkillTagsJson()),
            normalizeText(entity.getDefaultMode()) == null ? "agent_chat" : normalizeText(entity.getDefaultMode()),
            normalizeText(entity.getModelName()),
            normalizeText(entity.getSystemPrompt()),
            normalizeText(entity.getFirstUseGreeting()),
            readListJson(entity.getPreferredToolPrefixesJson()),
            readListJson(entity.getBoundMcpServiceIdsJson()),
            readListJson(entity.getBoundMcpToolNamesJson()),
            readListJson(entity.getBoundDocumentIdsJson()),
            readListJson(entity.getBoundDocumentTagsJson()),
            readToolConfigsJson(entity.getToolConfigsJson()),
            readRoutingSettingsJson(entity.getRoutingSettingsJson()),
            readListJson(entity.getQuickQuestionsJson()),
            normalizeMarketStatus(entity.getMarketStatus()) == null
                ? defaultMarketStatus(entity.getId())
                : normalizeMarketStatus(entity.getMarketStatus())
        );
    }

    private SkillVersionSnapshot toSkillVersionSnapshot(SkillConfigVersionEntity entity) {
        return new SkillVersionSnapshot(
            entity.getId(),
            entity.getSkillId(),
            entity.getAction(),
            entity.getLabel(),
            entity.getDescription(),
            readListJson(entity.getUsageScenariosJson()),
            readListJson(entity.getSkillTagsJson()),
            entity.getDefaultMode(),
            entity.getModelName(),
            entity.getSystemPrompt(),
            entity.getFirstUseGreeting(),
            readListJson(entity.getPreferredToolPrefixesJson()),
            readListJson(entity.getBoundMcpServiceIdsJson()),
            readListJson(entity.getBoundMcpToolNamesJson()),
            readListJson(entity.getBoundDocumentIdsJson()),
            readListJson(entity.getBoundDocumentTagsJson()),
            readToolConfigsJson(entity.getToolConfigsJson()),
            readRoutingSettingsJson(entity.getRoutingSettingsJson()),
            readListJson(entity.getQuickQuestionsJson()),
            normalizeMarketStatus(entity.getMarketStatus()) == null
                ? defaultMarketStatus(entity.getSkillId())
                : normalizeMarketStatus(entity.getMarketStatus()),
            entity.getCreatedAt() == null ? Instant.EPOCH.toEpochMilli() : entity.getCreatedAt().toEpochMilli()
        );
    }

    private void snapshotVersion(SkillConfigEntity source, String action) {
        if (source == null || source.getId() == null || source.getId().isBlank()) {
            return;
        }
        SkillConfigVersionEntity version = new SkillConfigVersionEntity();
        version.setSkillId(source.getId());
        version.setAction(action == null ? "update" : action.trim());
        version.setLabel(source.getLabel());
        version.setDescription(source.getDescription());
        version.setUsageScenariosJson(source.getUsageScenariosJson());
        version.setSkillTagsJson(source.getSkillTagsJson());
        version.setDefaultMode(source.getDefaultMode());
        version.setModelName(source.getModelName());
        version.setSystemPrompt(source.getSystemPrompt());
        version.setFirstUseGreeting(source.getFirstUseGreeting());
        version.setPreferredToolPrefixesJson(source.getPreferredToolPrefixesJson());
        version.setBoundMcpServiceIdsJson(source.getBoundMcpServiceIdsJson());
        version.setBoundMcpToolNamesJson(source.getBoundMcpToolNamesJson());
        version.setBoundDocumentIdsJson(source.getBoundDocumentIdsJson());
        version.setBoundDocumentTagsJson(source.getBoundDocumentTagsJson());
        version.setToolConfigsJson(source.getToolConfigsJson());
        version.setRoutingSettingsJson(source.getRoutingSettingsJson());
        version.setQuickQuestionsJson(source.getQuickQuestionsJson());
        version.setMarketStatus(source.getMarketStatus());
        versionRepository.save(version);
    }

    private SkillDefinition findGeneralOrDefault() {
        return repository.findById(DEFAULT_SKILL_ID)
            .map(this::toDefinition)
            .orElseGet(() -> repository.findAll().stream()
                .map(this::toDefinition)
                .sorted(Comparator.comparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No skill is registered")));
    }

    private void ensureSkillSchemaCompatibility() {
        ensureColumn("skill_config", "market_status", "varchar(32) default 'published'");
        ensureColumn("skill_config", "model_name", "varchar(128)");
        ensureColumn("skill_config", "preferred_tool_prefixes_json", "varchar(16000)");
        ensureColumn("skill_config", "bound_mcp_service_ids_json", "varchar(16000)");
        ensureColumn("skill_config", "bound_mcp_tool_names_json", "varchar(16000)");
        ensureColumn("skill_config", "bound_document_ids_json", "varchar(16000)");
        ensureColumn("skill_config", "bound_document_tags_json", "varchar(16000)");
        ensureColumn("skill_config", "tool_configs_json", "varchar(16000)");
        ensureColumn("skill_config", "routing_settings_json", "varchar(4000)");
        ensureColumn("skill_config", "quick_questions_json", "varchar(16000)");

        ensureColumn("skill_config_version", "market_status", "varchar(32)");
        ensureColumn("skill_config_version", "model_name", "varchar(128)");
        ensureColumn("skill_config_version", "preferred_tool_prefixes_json", "varchar(16000)");
        ensureColumn("skill_config_version", "bound_mcp_service_ids_json", "varchar(16000)");
        ensureColumn("skill_config_version", "bound_mcp_tool_names_json", "varchar(16000)");
        ensureColumn("skill_config_version", "bound_document_ids_json", "varchar(16000)");
        ensureColumn("skill_config_version", "bound_document_tags_json", "varchar(16000)");
        ensureColumn("skill_config_version", "tool_configs_json", "varchar(16000)");
        ensureColumn("skill_config_version", "routing_settings_json", "varchar(4000)");
        ensureColumn("skill_config_version", "quick_questions_json", "varchar(16000)");
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        if (!tableExists(tableName) || columnExists(tableName, columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute("alter table " + tableName + " add column " + columnName + " " + definition);
        } catch (DataAccessException ex) {
            if (!columnExists(tableName, columnName)) {
                throw ex;
            }
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns where lower(table_name) = ? and lower(column_name) = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT),
            columnName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    private SkillRoutingSettings defaultRoutingSettings() {
        return new SkillRoutingSettings(true, true, 3);
    }

    private String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("skill id is required");
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (!SKILL_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("skill id must match [a-z0-9_-], length 2-64");
        }
        return normalized;
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeMarketStatus(String value) {
        String status = normalizeText(value);
        if (status == null) {
            return null;
        }
        status = status.toLowerCase(Locale.ROOT);
        if (MARKET_STATUS_PUBLISHED.equals(status)
            || MARKET_STATUS_RECALLED.equals(status)
            || MARKET_STATUS_DRAFT.equals(status)) {
            return status;
        }
        throw new IllegalArgumentException("market status must be draft, published or recalled");
    }

    private String defaultMarketStatus(String skillId) {
        return MARKET_STATUS_DRAFT;
    }

    private List<String> normalizeList(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String item : input) {
            String trimmed = normalizeText(item);
            if (trimmed != null && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private List<SkillToolConfig> normalizeToolConfigs(List<SkillToolConfig> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<SkillToolConfig> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SkillToolConfig config : input) {
            if (config == null) {
                continue;
            }
            String toolName = normalizeText(config.toolName());
            if (toolName == null || !seen.add(toolName)) {
                continue;
            }
            normalized.add(new SkillToolConfig(
                toolName,
                normalizeText(config.displayName()),
                normalizeText(config.serviceId()),
                normalizeText(config.description()),
                normalizeList(config.tags()),
                normalizeText(config.permissionScope()) == null ? "read" : normalizeText(config.permissionScope()),
                config.callWeight() == null ? 5 : Math.max(0, Math.min(10, config.callWeight())),
                config.enabled() == null || config.enabled()
            ));
        }
        return List.copyOf(normalized);
    }

    private SkillRoutingSettings normalizeRoutingSettings(SkillRoutingSettings settings) {
        if (settings == null) {
            return defaultRoutingSettings();
        }
        return new SkillRoutingSettings(
            settings.smartSelectionEnabled() == null || settings.smartSelectionEnabled(),
            settings.limitParallelCalls() != null && settings.limitParallelCalls(),
            settings.maxParallelCalls() == null ? 3 : Math.max(1, Math.min(10, settings.maxParallelCalls()))
        );
    }

    private List<String> applyPrefixSelection(List<String> candidates, List<String> prefixes) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> normalizedPrefixes = normalizeList(prefixes);
        if (normalizedPrefixes.isEmpty()) {
            return List.of();
        }
        List<String> selected = candidates.stream()
            .filter(name -> normalizedPrefixes.stream().anyMatch(name::startsWith))
            .sorted()
            .toList();
        return selected.isEmpty() ? candidates.stream().sorted().toList() : selected;
    }

    private List<String> withDocumentWorkflowTool(List<String> selectedTools,
                                                  List<String> allTools,
                                                  SkillDefinition skill) {
        boolean hasDocumentScope = !normalizeList(skill.boundDocumentIds()).isEmpty()
            || !normalizeList(skill.boundDocumentTags()).isEmpty();
        if (!hasDocumentScope || allTools == null || !allTools.contains("document_search")) {
            return selectedTools == null ? List.of() : selectedTools;
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>(selectedTools == null ? List.of() : selectedTools);
        selected.add("document_search");
        return List.copyOf(selected);
    }

    private String writeListJson(List<String> list) {
        List<String> normalized = normalizeList(list);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize skill list", e);
        }
    }

    private String writeToolConfigsJson(List<SkillToolConfig> toolConfigs) {
        List<SkillToolConfig> normalized = normalizeToolConfigs(toolConfigs);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize tool configs", e);
        }
    }

    private String writeRoutingSettingsJson(SkillRoutingSettings routingSettings) {
        SkillRoutingSettings normalized = normalizeRoutingSettings(routingSettings);
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize routing settings", e);
        }
    }

    private List<String> readListJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return normalizeList(values);
        } catch (Exception ignored) {
            String[] parts = json.split("[,\\r\\n]");
            List<String> fallback = new ArrayList<>();
            for (String part : parts) {
                String trimmed = normalizeText(part);
                if (trimmed != null && !fallback.contains(trimmed)) {
                    fallback.add(trimmed);
                }
            }
            return List.copyOf(fallback);
        }
    }

    private List<SkillToolConfig> readToolConfigsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SkillToolConfig> values = objectMapper.readValue(json, new TypeReference<List<SkillToolConfig>>() {
            });
            return normalizeToolConfigs(values);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private SkillRoutingSettings readRoutingSettingsJson(String json) {
        if (json == null || json.isBlank()) {
            return defaultRoutingSettings();
        }
        try {
            return normalizeRoutingSettings(objectMapper.readValue(json, SkillRoutingSettings.class));
        } catch (Exception ignored) {
            return defaultRoutingSettings();
        }
    }

    public record SkillVersionSnapshot(
        String id,
        String skillId,
        String action,
        String label,
        String description,
        List<String> usageScenarios,
        List<String> skillTags,
        String defaultMode,
        String modelName,
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<String> boundDocumentIds,
        List<String> boundDocumentTags,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        List<String> quickQuestions,
        String marketStatus,
        Long createdAt
    ) {
    }
}
