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
import java.util.LinkedHashMap;
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
        "workflowConfig",
        "defaultDataAsset",
        "assetSelectionPolicy",
        "quickQuestions"
    );

    private final SkillConfigRepository repository;
    private final SkillConfigVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Performs the initialize defaults operation.
     */
    @PostConstruct
    @Transactional
    public void initializeDefaults() {
        ensureSkillSchemaCompatibility();
        ensureDefaultAgentPresent();
    }

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @Transactional(readOnly = true)
    public synchronized List<SkillDefinition> list() {
        return repository.findAll().stream()
            .map(this::toDefinition)
            .sorted(Comparator
                .comparing((SkillDefinition skill) -> !Boolean.TRUE.equals(skill.defaultAgent()))
                .thenComparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /**
     * Resolves the resolve.
     *
     * @param skillId the skill id value
     * @return the resolved resolve
     */
    @Transactional(readOnly = true)
    public synchronized SkillDefinition resolve(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return findDefaultAgentOrGeneralOrDefault();
        }
        String id = skillId.trim().toLowerCase(Locale.ROOT);
        return repository.findById(id)
            .map(this::toDefinition)
            .orElseGet(this::findDefaultAgentOrGeneralOrDefault);
    }

    /**
     * Returns whether is builtin skill.
     *
     * @param skillId the skill id value
     * @return whether the condition is satisfied
     */
    @Transactional(readOnly = true)
    public synchronized boolean isBuiltinSkill(String skillId) {
        return false;
    }

    /**
     * Performs the editable fields operation.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    @Transactional(readOnly = true)
    public synchronized List<String> editableFields(String skillId) {
        return CUSTOM_EDITABLE_FIELDS;
    }

    /**
     * Resolves the tools.
     *
     * @param skillId the skill id value
     * @param allTools the all tools value
     * @return the resolved tools
     */
    @Transactional(readOnly = true)
    public synchronized List<String> resolveTools(String skillId, Collection<String> allTools) {
        return resolveTools(skillId, allTools, Map.of());
    }

    /**
     * Resolves the tools.
     *
     * @param skillId the skill id value
     * @param allTools the all tools value
     * @param mcpToolsByServiceId the mcp tools by service id value
     * @return the resolved tools
     */
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

    /**
     * Performs the upsert operation.
     *
     * @param draft the draft value
     * @return the operation result
     */
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
            normalizeWorkflowConfig(draft.workflowConfig()),
            normalizeDefaultDataAsset(draft.defaultDataAsset()),
            normalizeAssetSelectionPolicy(draft.assetSelectionPolicy()),
            normalizeList(draft.quickQuestions()),
            marketStatus,
            resolveDefaultAgentFlag(draft.defaultAgent(), existing)
        );

        SkillConfigEntity entity = existing == null ? new SkillConfigEntity() : existing;
        boolean exists = existing != null;
        if (Boolean.TRUE.equals(normalized.defaultAgent())) {
            clearDefaultAgentExcept(id);
        }
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
        entity.setWorkflowConfigJson(writeWorkflowConfigJson(normalized.workflowConfig()));
        entity.setDefaultDataAssetJson(writeDefaultDataAssetJson(normalized.defaultDataAsset()));
        entity.setAssetSelectionPolicyJson(writeAssetSelectionPolicyJson(normalized.assetSelectionPolicy()));
        entity.setQuickQuestionsJson(writeListJson(normalized.quickQuestions()));
        entity.setMarketStatus(normalized.marketStatus());
        entity.setDefaultAgent(Boolean.TRUE.equals(normalized.defaultAgent()));

        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, exists ? "update" : "create");
        return toDefinition(saved);
    }

    /**
     * Lists the versions.
     *
     * @param skillId the skill id value
     * @return the versions list
     */
    @Transactional(readOnly = true)
    public synchronized List<SkillVersionSnapshot> listVersions(String skillId) {
        String id = normalizeId(skillId);
        return versionRepository.findTop30BySkillIdOrderByCreatedAtDesc(id).stream()
            .map(this::toSkillVersionSnapshot)
            .toList();
    }

    /**
     * Performs the rollback to version operation.
     *
     * @param skillId the skill id value
     * @param versionId the version id value
     * @return the operation result
     */
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
        current.setWorkflowConfigJson(target.getWorkflowConfigJson());
        current.setDefaultDataAssetJson(target.getDefaultDataAssetJson());
        current.setAssetSelectionPolicyJson(target.getAssetSelectionPolicyJson());
        current.setQuickQuestionsJson(target.getQuickQuestionsJson());
        current.setMarketStatus(normalizeMarketStatus(target.getMarketStatus()) == null
            ? defaultMarketStatus(id)
            : normalizeMarketStatus(target.getMarketStatus()));
        if (target.isDefaultAgent()) {
            clearDefaultAgentExcept(id);
        }
        current.setDefaultAgent(target.isDefaultAgent());
        SkillConfigEntity saved = repository.save(current);
        snapshotVersion(saved, "rollback");
        return toDefinition(saved);
    }

    /**
     * Sets one skill as the default Agent capability.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    @Transactional
    public synchronized SkillDefinition setDefaultAgent(String skillId) {
        String id = normalizeId(skillId);
        SkillConfigEntity entity = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("skill not found: " + id));
        clearDefaultAgentExcept(id);
        entity.setDefaultAgent(true);
        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, "default_agent");
        return toDefinition(saved);
    }

    /**
     * Publishes the to market.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    @Transactional
    public synchronized SkillDefinition publishToMarket(String skillId) {
        return setMarketStatus(skillId, MARKET_STATUS_PUBLISHED, "publish");
    }

    /**
     * Performs the recall from market operation.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    @Transactional
    public synchronized SkillDefinition recallFromMarket(String skillId) {
        return setMarketStatus(skillId, MARKET_STATUS_RECALLED, "recall");
    }

    /**
     * Sets the market status.
     *
     * @param skillId the skill id value
     * @param status the status value
     * @param action the action value
     * @return the operation result
     */
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

    /**
     * Returns whether delete.
     *
     * @param skillId the skill id value
     * @return whether the condition is satisfied
     */
    @Transactional
    public synchronized boolean delete(String skillId) {
        String id = normalizeId(skillId);
        SkillConfigEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            return false;
        }
        if (entity.isDefaultAgent()) {
            throw new IllegalArgumentException("default Agent capability cannot be deleted");
        }
        repository.deleteById(id);
        return true;
    }

    /**
     * Converts the value to definition.
     *
     * @param entity the entity value
     * @return the converted definition
     */
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
            readWorkflowConfigJson(entity.getWorkflowConfigJson()),
            readDefaultDataAssetJson(entity.getDefaultDataAssetJson()),
            readAssetSelectionPolicyJson(entity.getAssetSelectionPolicyJson()),
            readListJson(entity.getQuickQuestionsJson()),
            normalizeMarketStatus(entity.getMarketStatus()) == null
                ? defaultMarketStatus(entity.getId())
                : normalizeMarketStatus(entity.getMarketStatus()),
            entity.isDefaultAgent()
        );
    }

    /**
     * Converts the value to skill version snapshot.
     *
     * @param entity the entity value
     * @return the converted skill version snapshot
     */
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
            readWorkflowConfigJson(entity.getWorkflowConfigJson()),
            readDefaultDataAssetJson(entity.getDefaultDataAssetJson()),
            readAssetSelectionPolicyJson(entity.getAssetSelectionPolicyJson()),
            readListJson(entity.getQuickQuestionsJson()),
            normalizeMarketStatus(entity.getMarketStatus()) == null
                ? defaultMarketStatus(entity.getSkillId())
                : normalizeMarketStatus(entity.getMarketStatus()),
            entity.isDefaultAgent(),
            entity.getCreatedAt() == null ? Instant.EPOCH.toEpochMilli() : entity.getCreatedAt().toEpochMilli()
        );
    }

    /**
     * Performs the snapshot version operation.
     *
     * @param source the source value
     * @param action the action value
     */
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
        version.setWorkflowConfigJson(source.getWorkflowConfigJson());
        version.setDefaultDataAssetJson(source.getDefaultDataAssetJson());
        version.setAssetSelectionPolicyJson(source.getAssetSelectionPolicyJson());
        version.setQuickQuestionsJson(source.getQuickQuestionsJson());
        version.setMarketStatus(source.getMarketStatus());
        version.setDefaultAgent(source.isDefaultAgent());
        versionRepository.save(version);
    }

    /**
     * Ensures one default Agent capability exists after schema migration.
     */
    private void ensureDefaultAgentPresent() {
        if (!repository.findByDefaultAgentTrue().isEmpty()) {
            return;
        }
        SkillConfigEntity fallback = repository.findById(DEFAULT_SKILL_ID)
            .orElseGet(() -> repository.findAll().stream()
                .sorted(Comparator.comparing(SkillConfigEntity::getLabel, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElse(null));
        if (fallback == null) {
            return;
        }
        fallback.setDefaultAgent(true);
        repository.save(fallback);
    }

    /**
     * Clears default Agent flags from all skills except the provided id.
     *
     * @param skillId the skill id value
     */
    private void clearDefaultAgentExcept(String skillId) {
        List<SkillConfigEntity> defaultAgents = repository.findByDefaultAgentTrue();
        List<SkillConfigEntity> changed = new ArrayList<>();
        for (SkillConfigEntity entity : defaultAgents) {
            if (entity.getId() != null && entity.getId().equals(skillId)) {
                continue;
            }
            entity.setDefaultAgent(false);
            changed.add(entity);
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
    }

    /**
     * Resolves whether the current skill should be marked as default Agent.
     *
     * @param requestedDefaultAgent the requested default agent flag
     * @param existing the existing entity value
     * @return whether this skill is the default Agent
     */
    private boolean resolveDefaultAgentFlag(Boolean requestedDefaultAgent, SkillConfigEntity existing) {
        if (requestedDefaultAgent != null) {
            return Boolean.TRUE.equals(requestedDefaultAgent);
        }
        if (existing != null) {
            return existing.isDefaultAgent();
        }
        return repository.findByDefaultAgentTrue().isEmpty();
    }

    /**
     * Finds the general or default.
     *
     * @return the matching general or default
     */
    private SkillDefinition findDefaultAgentOrGeneralOrDefault() {
        List<SkillConfigEntity> defaultAgents = repository.findByDefaultAgentTrue();
        if (!defaultAgents.isEmpty()) {
            return defaultAgents.stream()
                .map(this::toDefinition)
                .sorted(Comparator.comparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No skill is registered"));
        }
        return repository.findById(DEFAULT_SKILL_ID)
            .map(this::toDefinition)
            .orElseGet(() -> repository.findAll().stream()
                .map(this::toDefinition)
                .sorted(Comparator.comparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No skill is registered")));
    }

    /**
     * Ensures the skill schema compatibility.
     */
    private void ensureSkillSchemaCompatibility() {
        ensureColumn("skill_config", "market_status", "varchar(32) default 'published'");
        ensureColumn("skill_config", "default_agent", "boolean default false");
        ensureColumn("skill_config", "model_name", "varchar(128)");
        ensureColumn("skill_config", "preferred_tool_prefixes_json", "text");
        ensureColumn("skill_config", "bound_mcp_service_ids_json", "text");
        ensureColumn("skill_config", "bound_mcp_tool_names_json", "text");
        ensureColumn("skill_config", "bound_document_ids_json", "text");
        ensureColumn("skill_config", "bound_document_tags_json", "text");
        ensureColumn("skill_config", "tool_configs_json", "text");
        ensureColumn("skill_config", "routing_settings_json", "text");
        ensureColumn("skill_config", "workflow_config_json", "text");
        ensureColumn("skill_config", "default_data_asset_json", "text");
        ensureColumn("skill_config", "asset_selection_policy_json", "text");
        ensureColumn("skill_config", "quick_questions_json", "text");

        ensureColumn("skill_config_version", "market_status", "varchar(32)");
        ensureColumn("skill_config_version", "default_agent", "boolean default false");
        ensureColumn("skill_config_version", "model_name", "varchar(128)");
        ensureColumn("skill_config_version", "preferred_tool_prefixes_json", "text");
        ensureColumn("skill_config_version", "bound_mcp_service_ids_json", "text");
        ensureColumn("skill_config_version", "bound_mcp_tool_names_json", "text");
        ensureColumn("skill_config_version", "bound_document_ids_json", "text");
        ensureColumn("skill_config_version", "bound_document_tags_json", "text");
        ensureColumn("skill_config_version", "tool_configs_json", "text");
        ensureColumn("skill_config_version", "routing_settings_json", "text");
        ensureColumn("skill_config_version", "workflow_config_json", "text");
        ensureColumn("skill_config_version", "default_data_asset_json", "text");
        ensureColumn("skill_config_version", "asset_selection_policy_json", "text");
        ensureColumn("skill_config_version", "quick_questions_json", "text");
    }

    /**
     * Ensures the column.
     *
     * @param tableName the table name value
     * @param columnName the column name value
     * @param definition the definition value
     */
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

    /**
     * Returns whether table exists.
     *
     * @param tableName the table name value
     * @return whether the condition is satisfied
     */
    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    /**
     * Returns whether column exists.
     *
     * @param tableName the table name value
     * @param columnName the column name value
     * @return whether the condition is satisfied
     */
    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns where lower(table_name) = ? and lower(column_name) = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT),
            columnName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    /**
     * Performs the default routing settings operation.
     *
     * @return the operation result
     */
    private SkillRoutingSettings defaultRoutingSettings() {
        return new SkillRoutingSettings(true, true, 3, 3);
    }

    /**
     * Normalizes the id.
     *
     * @param id the id value
     * @return the operation result
     */
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

    /**
     * Normalizes the text.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Normalizes the market status.
     *
     * @param value the value value
     * @return the operation result
     */
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

    /**
     * Performs the default market status operation.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    private String defaultMarketStatus(String skillId) {
        return MARKET_STATUS_DRAFT;
    }

    /**
     * Normalizes the list.
     *
     * @param input the input value
     * @return the operation result
     */
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

    /**
     * Normalizes the tool configs.
     *
     * @param input the input value
     * @return the operation result
     */
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

    /**
     * Normalizes the routing settings.
     *
     * @param settings the settings value
     * @return the operation result
     */
    private SkillRoutingSettings normalizeRoutingSettings(SkillRoutingSettings settings) {
        if (settings == null) {
            return defaultRoutingSettings();
        }
        return new SkillRoutingSettings(
            settings.smartSelectionEnabled() == null || settings.smartSelectionEnabled(),
            settings.limitParallelCalls() != null && settings.limitParallelCalls(),
            settings.maxParallelCalls() == null ? 3 : Math.max(1, Math.min(10, settings.maxParallelCalls())),
            settings.maxRelevantMcpTools() == null ? 3 : Math.max(1, Math.min(20, settings.maxRelevantMcpTools()))
        );
    }

    /**
     * Normalizes the workflow config.
     *
     * @param config the config value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeWorkflowConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        Object enabled = config.get("enabled");
        normalized.put("enabled", !(enabled instanceof Boolean bool) || bool);
        Object configuredEnvironment = firstObject(config, "runtimeEnvironment", "runtime_environment");
        String runtimeEnvironment = normalizeRuntimeEnvironment(configuredEnvironment);
        if (runtimeEnvironment != null) {
            normalized.put("runtimeEnvironment", runtimeEnvironment);
        }
        putText(normalized, "workflow", firstObject(config, "workflow", "workflowId", "id", "name"));
        Object mcpWorkflow = config.get("mcpWorkflow");
        if (mcpWorkflow instanceof List<?> || mcpWorkflow instanceof Map<?, ?>) {
            normalized.put("mcpWorkflow", mcpWorkflow);
        }
        Object strategy = firstObject(config, "executionStrategy", "execution_strategy");
        if (strategy instanceof Map<?, ?> strategyMap) {
            normalized.put("executionStrategy", new LinkedHashMap<>((Map<String, Object>) strategyMap));
        }
        Map<String, Object> configuredDependencies = new LinkedHashMap<>();
        Object dependencies = firstObject(config, "toolDependencies", "tool_dependencies");
        if (dependencies instanceof Map<?, ?> dependencyMap) {
            dependencyMap.forEach((key, value) -> {
                String toolName = normalizeText(String.valueOf(key));
                if (toolName != null) {
                    configuredDependencies.put(toolName, value);
                }
            });
        }
        Object steps = config.get("steps");
        if (steps instanceof List<?> list) {
            List<Map<String, Object>> normalizedSteps = new ArrayList<>();
            Map<String, Object> normalizedDependencies = new LinkedHashMap<>();
            int index = 1;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawStep)) {
                    continue;
                }
                Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) rawStep);
                Object tool = firstObject(step, "tool", "toolName");
                String toolName = tool == null ? null : normalizeText(String.valueOf(tool));
                Object parallelSteps = firstObject(step, "parallelSteps", "parallel_steps");
                List<String> normalizedParallelSteps = stringValues(parallelSteps);
                if ((toolName == null || toolName.isBlank()) && normalizedParallelSteps.isEmpty()) {
                    continue;
                }
                if (toolName != null) {
                    step.put("tool", toolName);
                } else {
                    step.remove("tool");
                }
                if (!normalizedParallelSteps.isEmpty()) {
                    step.put("parallelSteps", normalizedParallelSteps);
                }
                List<String> dependsOn = stringValues(firstObject(step, "dependsOn", "depends_on", "dependencies", "requires", "after"));
                if (dependsOn.isEmpty() && toolName != null) {
                    Object configuredDependency = configuredDependencies.get(toolName);
                    if (configuredDependency instanceof Map<?, ?> dependencySpec) {
                        dependsOn = stringValues(firstObjectFromMap(dependencySpec, "dependsOn", "depends_on", "dependencies", "requires", "after"));
                    } else {
                        dependsOn = stringValues(configuredDependency);
                    }
                }
                String currentToolName = toolName;
                dependsOn = dependsOn.stream()
                    .filter(dependency -> currentToolName == null || !dependency.equals(currentToolName))
                    .distinct()
                    .toList();
                if (!dependsOn.isEmpty()) {
                    step.put("dependsOn", dependsOn);
                    if (toolName != null) {
                        normalizedDependencies.put(toolName, Map.of("dependsOn", dependsOn));
                    }
                } else {
                    step.remove("dependsOn");
                    step.remove("depends_on");
                    step.remove("dependencies");
                    step.remove("requires");
                    step.remove("after");
                }
                step.putIfAbsent("step", index);
                normalizedSteps.add(step);
                index++;
            }
            normalized.put("steps", normalizedSteps);
            if (!normalizedDependencies.isEmpty()) {
                normalized.put("toolDependencies", normalizedDependencies);
            }
        }
        Object parallelSteps = firstObject(config, "parallelSteps", "parallel_steps");
        List<String> normalizedParallelSteps = stringValues(parallelSteps);
        if (!normalizedParallelSteps.isEmpty()) {
            normalized.put("parallelSteps", normalizedParallelSteps);
        }
        return normalized;
    }

    private String normalizeRuntimeEnvironment(Object value) {
        String environment = value == null ? null : normalizeText(String.valueOf(value));
        if (environment == null) {
            return null;
        }
        String canonical = environment.toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "TEST", "UAT", "PROD").contains(canonical)) {
            throw new IllegalArgumentException(
                "workflowConfig.runtimeEnvironment must be one of DEV, TEST, UAT, PROD"
            );
        }
        return canonical;
    }

    /**
     * Performs the apply prefix selection operation.
     *
     * @param candidates the candidates value
     * @param prefixes the prefixes value
     * @return the operation result
     */
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

    /**
     * Performs the with document workflow tool operation.
     *
     * @param selectedTools the selected tools value
     * @param allTools the all tools value
     * @param skill the skill value
     * @return the operation result
     */
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

    /**
     * Writes the list json.
     *
     * @param list the list value
     * @return the operation result
     */
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

    /**
     * Writes the tool configs json.
     *
     * @param toolConfigs the tool configs value
     * @return the operation result
     */
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

    /**
     * Writes the routing settings json.
     *
     * @param routingSettings the routing settings value
     * @return the operation result
     */
    private String writeRoutingSettingsJson(SkillRoutingSettings routingSettings) {
        SkillRoutingSettings normalized = normalizeRoutingSettings(routingSettings);
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize routing settings", e);
        }
    }

    /**
     * Writes the workflow config json.
     *
     * @param workflowConfig the workflow config value
     * @return the operation result
     */
    private String writeWorkflowConfigJson(Map<String, Object> workflowConfig) {
        Map<String, Object> normalized = normalizeWorkflowConfig(workflowConfig);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize workflow config", e);
        }
    }

    private String writeDefaultDataAssetJson(SkillDefinition.DefaultDataAsset defaultDataAsset) {
        SkillDefinition.DefaultDataAsset normalized = normalizeDefaultDataAsset(defaultDataAsset);
        if (normalized == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize default data asset", e);
        }
    }

    private String writeAssetSelectionPolicyJson(SkillDefinition.AssetSelectionPolicy policy) {
        SkillDefinition.AssetSelectionPolicy normalized = normalizeAssetSelectionPolicy(policy);
        if (normalized == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize asset selection policy", e);
        }
    }

    /**
     * Reads the list json.
     *
     * @param json the json value
     * @return the operation result
     */
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

    /**
     * Reads the tool configs json.
     *
     * @param json the json value
     * @return the operation result
     */
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

    /**
     * Reads the routing settings json.
     *
     * @param json the json value
     * @return the operation result
     */
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

    /**
     * Reads the workflow config json.
     *
     * @param json the json value
     * @return the operation result
     */
    private Map<String, Object> readWorkflowConfigJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> values = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return normalizeWorkflowConfig(values);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private SkillDefinition.DefaultDataAsset readDefaultDataAssetJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return normalizeDefaultDataAsset(objectMapper.readValue(json, SkillDefinition.DefaultDataAsset.class));
        } catch (Exception ignored) {
            return null;
        }
    }

    private SkillDefinition.AssetSelectionPolicy readAssetSelectionPolicyJson(String json) {
        if (json == null || json.isBlank()) {
            return defaultAssetSelectionPolicy();
        }
        try {
            return normalizeAssetSelectionPolicy(objectMapper.readValue(json, SkillDefinition.AssetSelectionPolicy.class));
        } catch (Exception ignored) {
            return defaultAssetSelectionPolicy();
        }
    }

    private SkillDefinition.DefaultDataAsset normalizeDefaultDataAsset(SkillDefinition.DefaultDataAsset asset) {
        if (asset == null) {
            return null;
        }
        String assetId = normalizeText(asset.assetId());
        String assetName = normalizeText(asset.assetName());
        if (assetId == null && assetName == null) {
            return null;
        }
        return new SkillDefinition.DefaultDataAsset(
            assetId,
            assetName,
            "DATABASE",
            normalizeText(asset.warehouseId()),
            asset.enabled() == null ? Boolean.TRUE : asset.enabled()
        );
    }

    private SkillDefinition.AssetSelectionPolicy normalizeAssetSelectionPolicy(SkillDefinition.AssetSelectionPolicy policy) {
        if (policy == null) {
            return defaultAssetSelectionPolicy();
        }
        String strategy = normalizeText(policy.strategy());
        Double minRelevanceScore = policy.minRelevanceScore();
        if (minRelevanceScore == null || minRelevanceScore.isNaN() || minRelevanceScore < 0.0D) {
            minRelevanceScore = 0.7D;
        }
        minRelevanceScore = Math.min(1.0D, minRelevanceScore);
        return new SkillDefinition.AssetSelectionPolicy(
            strategy == null ? "SEARCH_FIRST_DEFAULT_FALLBACK" : strategy,
            minRelevanceScore,
            policy.fallbackWhenEmpty() == null ? Boolean.TRUE : policy.fallbackWhenEmpty(),
            policy.fallbackWhenInvalid() == null ? Boolean.TRUE : policy.fallbackWhenInvalid()
        );
    }

    private SkillDefinition.AssetSelectionPolicy defaultAssetSelectionPolicy() {
        return new SkillDefinition.AssetSelectionPolicy(
            "SEARCH_FIRST_DEFAULT_FALLBACK",
            0.7D,
            true,
            true
        );
    }

    /**
     * Performs the first object operation.
     *
     * @param values the values value
     * @param keys the keys value
     * @return the operation result
     */
    private Object firstObject(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    /**
     * Performs the first object from map operation.
     *
     * @param values the values value
     * @param keys the keys value
     * @return the operation result
     */
    private Object firstObjectFromMap(Map<?, ?> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    /**
     * Performs the string values operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<String> stringValues(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = normalizeText(item == null ? null : String.valueOf(item));
                if (text != null && !values.contains(text)) {
                    values.add(text);
                }
            }
            return List.copyOf(values);
        }
        String text = normalizeText(String.valueOf(value));
        if (text == null) {
            return List.of();
        }
        for (String item : text.split("[,;\\r\\n]")) {
            String normalized = normalizeText(item);
            if (normalized != null && !values.contains(normalized)) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    /**
     * Stores the text.
     *
     * @param target the target value
     * @param key the key value
     * @param value the value value
     */
    private void putText(Map<String, Object> target, String key, Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text != null && !text.isBlank()) {
            target.put(key, text);
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
        Map<String, Object> workflowConfig,
        SkillDefinition.DefaultDataAsset defaultDataAsset,
        SkillDefinition.AssetSelectionPolicy assetSelectionPolicy,
        List<String> quickQuestions,
        String marketStatus,
        Boolean defaultAgent,
        Long createdAt
    ) {
    }
}
