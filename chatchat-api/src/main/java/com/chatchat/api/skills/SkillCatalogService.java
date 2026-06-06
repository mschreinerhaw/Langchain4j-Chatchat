package com.chatchat.api.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persistent skill catalog for enterprise scenarios.
 */
@Service
@RequiredArgsConstructor
public class SkillCatalogService {

    private static final String DEFAULT_SKILL_ID = "general";
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{2,64}$");
    private static final List<String> BUILTIN_ORDER = List.of(
        "general",
        "risk",
        "operations",
        "report",
        "github_test"
    );
    private static final List<String> BUILTIN_EDITABLE_FIELDS = List.of(
        "label",
        "description",
        "usageScenarios",
        "skillTags",
        "defaultMode",
        "systemPrompt",
        "firstUseGreeting",
        "boundMcpServiceIds",
        "boundMcpToolNames",
        "toolConfigs",
        "routingSettings",
        "quickQuestions"
    );
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

    private static final String ROUTING_POLICY_MARKER = "【工具路由策略】";
    private static final String ROUTING_POLICY = String.join("\n",
        "【工具路由策略】",
        "1. 先判断当前问题是否需要外部实时数据或企业系统数据。",
        "2. 若问题依赖企业系统、仓库、告警等数据，优先调用 MCP 工具（通常是 mcp_ 前缀工具），并按工具要求组织参数。",
        "3. 若无需调用 MCP 也能准确回答，则直接回答，不要强行调用工具。",
        "4. 若需要公开网络信息且 MCP 不适配，可使用联网搜索工具（如 web_search）。",
        "5. 最终回答中说明依据来源：MCP 工具结果、联网搜索结果，或模型直接推理。"
    );

    private final SkillConfigRepository repository;
    private final SkillConfigVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    @PostConstruct
    @Transactional
    public void initializeDefaults() {
        ensureDefaultSkills();
        ensureBuiltinRoutingPolicyPresent();
    }

    @Transactional(readOnly = true)
    public synchronized List<SkillDefinition> list() {
        ensureDefaultSkills();
        ensureBuiltinRoutingPolicyPresent();

        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < BUILTIN_ORDER.size(); i++) {
            order.put(BUILTIN_ORDER.get(i), i);
        }

        return repository.findAll().stream()
            .map(this::toDefinition)
            .sorted(Comparator
                .comparingInt((SkillDefinition s) -> order.getOrDefault(s.id(), Integer.MAX_VALUE))
                .thenComparing(SkillDefinition::label, String.CASE_INSENSITIVE_ORDER))
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
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        return BUILTIN_ORDER.contains(skillId.trim().toLowerCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public synchronized List<String> editableFields(String skillId) {
        return isBuiltinSkill(skillId) ? BUILTIN_EDITABLE_FIELDS : CUSTOM_EDITABLE_FIELDS;
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
            return applyPrefixSelection(sortedAllTools, prefixes);
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
        selected.addAll(applyPrefixSelection(nonMcpTools, prefixes));

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

        return List.copyOf(selected);
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

        SkillDefinition normalized = new SkillDefinition(
            id,
            label,
            normalizeText(draft.description()),
            normalizeList(draft.usageScenarios()),
            normalizeList(draft.skillTags()),
            defaultMode,
            normalizeText(draft.systemPrompt()),
            normalizeText(draft.firstUseGreeting()),
            normalizeList(draft.preferredToolPrefixes()),
            normalizeList(draft.boundMcpServiceIds()),
            boundMcpToolNames,
            toolConfigs,
            normalizeRoutingSettings(draft.routingSettings()),
            normalizeList(draft.quickQuestions())
        );

        SkillConfigEntity entity = repository.findById(id).orElseGet(SkillConfigEntity::new);
        boolean exists = repository.existsById(id);
        entity.setId(id);
        entity.setLabel(normalized.label());
        entity.setDescription(normalized.description());
        entity.setUsageScenariosJson(writeListJson(normalized.usageScenarios()));
        entity.setSkillTagsJson(writeListJson(normalized.skillTags()));
        entity.setDefaultMode(normalized.defaultMode());
        entity.setFirstUseGreeting(normalized.firstUseGreeting());

        boolean builtinSkill = isBuiltinSkill(id);
        if (builtinSkill) {
            SkillDefinition baseline = repository.findById(id)
                .map(this::toDefinition)
                .orElseGet(() -> builtinDefaultById(id).orElse(defaultGeneralSkill()));
            String preferredPrompt = normalized.systemPrompt() == null ? baseline.systemPrompt() : normalized.systemPrompt();
            entity.setSystemPrompt(withRoutingPolicy(preferredPrompt));
            entity.setPreferredToolPrefixesJson(writeListJson(baseline.preferredToolPrefixes()));
        } else {
            entity.setSystemPrompt(normalized.systemPrompt());
            entity.setPreferredToolPrefixesJson(writeListJson(normalized.preferredToolPrefixes()));
        }
        entity.setBoundMcpServiceIdsJson(writeListJson(normalized.boundMcpServiceIds()));
        entity.setBoundMcpToolNamesJson(writeListJson(normalized.boundMcpToolNames()));
        entity.setToolConfigsJson(writeToolConfigsJson(normalized.toolConfigs()));
        entity.setRoutingSettingsJson(writeRoutingSettingsJson(normalized.routingSettings()));
        entity.setQuickQuestionsJson(writeListJson(normalized.quickQuestions()));

        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, exists ? "update" : "create");
        ensureDefaultSkillPresent();
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
        String restoredPrompt = target.getSystemPrompt();
        if (isBuiltinSkill(id)) {
            restoredPrompt = withRoutingPolicy(restoredPrompt);
        }
        current.setSystemPrompt(restoredPrompt);
        current.setFirstUseGreeting(target.getFirstUseGreeting());
        if (isBuiltinSkill(id)) {
            SkillDefinition baseline = repository.findById(id)
                .map(this::toDefinition)
                .orElseGet(() -> builtinDefaultById(id).orElse(defaultGeneralSkill()));
            current.setPreferredToolPrefixesJson(writeListJson(baseline.preferredToolPrefixes()));
        } else {
            current.setPreferredToolPrefixesJson(target.getPreferredToolPrefixesJson());
        }
        current.setBoundMcpServiceIdsJson(target.getBoundMcpServiceIdsJson());
        current.setBoundMcpToolNamesJson(target.getBoundMcpToolNamesJson());
        current.setToolConfigsJson(target.getToolConfigsJson());
        current.setRoutingSettingsJson(target.getRoutingSettingsJson());
        current.setQuickQuestionsJson(target.getQuickQuestionsJson());
        SkillConfigEntity saved = repository.save(current);
        snapshotVersion(saved, "rollback");
        return toDefinition(saved);
    }

    @Transactional
    public synchronized boolean delete(String skillId) {
        String id = normalizeId(skillId);
        if (isBuiltinSkill(id)) {
            throw new IllegalArgumentException("builtin skill cannot be deleted");
        }
        boolean exists = repository.existsById(id);
        if (exists) {
            repository.deleteById(id);
        }
        ensureDefaultSkillPresent();
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
            normalizeText(entity.getSystemPrompt()),
            normalizeText(entity.getFirstUseGreeting()),
            readListJson(entity.getPreferredToolPrefixesJson()),
            readListJson(entity.getBoundMcpServiceIdsJson()),
            readListJson(entity.getBoundMcpToolNamesJson()),
            readToolConfigsJson(entity.getToolConfigsJson()),
            readRoutingSettingsJson(entity.getRoutingSettingsJson()),
            readListJson(entity.getQuickQuestionsJson())
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
            entity.getSystemPrompt(),
            entity.getFirstUseGreeting(),
            readListJson(entity.getPreferredToolPrefixesJson()),
            readListJson(entity.getBoundMcpServiceIdsJson()),
            readListJson(entity.getBoundMcpToolNamesJson()),
            readToolConfigsJson(entity.getToolConfigsJson()),
            readRoutingSettingsJson(entity.getRoutingSettingsJson()),
            readListJson(entity.getQuickQuestionsJson()),
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
        version.setSystemPrompt(source.getSystemPrompt());
        version.setFirstUseGreeting(source.getFirstUseGreeting());
        version.setPreferredToolPrefixesJson(source.getPreferredToolPrefixesJson());
        version.setBoundMcpServiceIdsJson(source.getBoundMcpServiceIdsJson());
        version.setBoundMcpToolNamesJson(source.getBoundMcpToolNamesJson());
        version.setToolConfigsJson(source.getToolConfigsJson());
        version.setRoutingSettingsJson(source.getRoutingSettingsJson());
        version.setQuickQuestionsJson(source.getQuickQuestionsJson());
        versionRepository.save(version);
    }

    private SkillDefinition findGeneralOrDefault() {
        return repository.findById(DEFAULT_SKILL_ID)
            .map(this::toDefinition)
            .orElseGet(this::defaultGeneralSkill);
    }

    private void ensureDefaultSkillPresent() {
        if (!repository.existsById(DEFAULT_SKILL_ID)) {
            saveDefaultIfMissing(defaultGeneralSkill());
        }
    }

    private void ensureDefaultSkills() {
        for (SkillDefinition definition : defaultSkills()) {
            saveDefaultIfMissing(definition);
        }
    }

    private void ensureBuiltinRoutingPolicyPresent() {
        for (String skillId : BUILTIN_ORDER) {
            repository.findById(skillId).ifPresent(entity -> {
                String currentPrompt = normalizeText(entity.getSystemPrompt());
                String mergedPrompt = appendRoutingPolicy(currentPrompt);
                if (!mergedPrompt.equals(currentPrompt)) {
                    entity.setSystemPrompt(mergedPrompt);
                    repository.save(entity);
                }
            });
        }
    }

    private void saveDefaultIfMissing(SkillDefinition definition) {
        if (repository.existsById(definition.id())) {
            return;
        }
        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId(definition.id());
        entity.setLabel(definition.label());
        entity.setDescription(definition.description());
        entity.setUsageScenariosJson(writeListJson(definition.usageScenarios()));
        entity.setSkillTagsJson(writeListJson(definition.skillTags()));
        entity.setDefaultMode(definition.defaultMode());
        entity.setSystemPrompt(definition.systemPrompt());
        entity.setFirstUseGreeting(definition.firstUseGreeting());
        entity.setPreferredToolPrefixesJson(writeListJson(definition.preferredToolPrefixes()));
        entity.setBoundMcpServiceIdsJson(writeListJson(definition.boundMcpServiceIds()));
        entity.setBoundMcpToolNamesJson(writeListJson(definition.boundMcpToolNames()));
        entity.setToolConfigsJson(writeToolConfigsJson(definition.toolConfigs()));
        entity.setRoutingSettingsJson(writeRoutingSettingsJson(definition.routingSettings()));
        entity.setQuickQuestionsJson(writeListJson(definition.quickQuestions()));
        SkillConfigEntity saved = repository.save(entity);
        snapshotVersion(saved, "seed");
    }

    private List<SkillDefinition> defaultSkills() {
        return List.of(
            defaultGeneralSkill(),
            new SkillDefinition(
                "risk",
                "风险分析",
                "聚焦风险识别、归因和处置建议",
                List.of(),
                List.of(),
                "agent_chat",
                withRoutingPolicy("你是风控智能助手。请基于证据给出可执行结论，并明确风险优先级。"),
                defaultFirstUseGreeting("risk"),
                List.of("mcp_", "web_search", "calculator"),
                List.of(),
                List.of(),
                List.of(),
                defaultRoutingSettings(),
                List.of(
                    "今天最关键的风险事件有哪些？",
                    "本周风险评分上升最快的客群是谁？",
                    "给出分支机构可执行的风险处置清单"
                )
            ),
            new SkillDefinition(
                "operations",
                "运营分析",
                "聚焦经营指标、漏斗转化与异常波动",
                List.of(),
                List.of(),
                "agent_chat",
                withRoutingPolicy("你是运营分析助手。请围绕趋势、异常、原因和行动建议组织答案。"),
                defaultFirstUseGreeting("operations"),
                List.of("mcp_", "web_search", "calculator"),
                List.of(),
                List.of(),
                List.of(),
                defaultRoutingSettings(),
                List.of(
                    "对比本周和上周核心指标变化",
                    "解释转化率下降最明显的环节",
                    "给出下周优先级最高的三项运营动作"
                )
            ),
            new SkillDefinition(
                "report",
                "报告解读",
                "对结构化报告进行重点提炼与问题识别",
                List.of(),
                List.of(),
                "agent_chat",
                withRoutingPolicy("你是报告解读助手。请输出结构化摘要，并标注关键信息、风险与行动项。"),
                defaultFirstUseGreeting("report"),
                List.of("mcp_", "web_search", "calculator"),
                List.of(),
                List.of(),
                List.of(),
                defaultRoutingSettings(),
                List.of(
                    "把这份报告总结成 5 条关键结论",
                    "找出口径不一致或数据异常的地方",
                    "输出一页会议可用的行动摘要"
                )
            ),
            new SkillDefinition(
                "github_test",
                "GitHub 测试",
                "用于测试 GitHub MCP 连接、仓库检索与 Issue/PR 流程",
                List.of(
                    "查询 GitHub 仓库信息和代码内容",
                    "搜索相关开源项目和文档",
                    "管理 issues 和 pull requests",
                    "获取开发者和项目信息"
                ),
                List.of("GitHub", "代码检索", "项目管理"),
                "agent_chat",
                withRoutingPolicy("你是 GitHub 测试助手。优先使用 MCP 工具返回可验证结果，并在失败时给出明确排查建议。"),
                defaultFirstUseGreeting("github_test"),
                List.of("mcp_github", "mcp_", "web_search"),
                List.of(),
                List.of(),
                List.of(),
                defaultRoutingSettings(),
                List.of(
                    "列出目标仓库最近打开的 5 个 Issue",
                    "查询最近一次失败的 GitHub Actions 工作流",
                    "总结最近 3 个已合并 PR 的改动重点"
                )
            )
        );
    }

    private SkillDefinition defaultGeneralSkill() {
        return new SkillDefinition(
            "general",
            "通用助手",
            "通用企业问答与工具协同",
            List.of(),
            List.of(),
            "agent_chat",
            withRoutingPolicy("你是企业智能助手。请以准确、简洁、可执行为优先。"),
            defaultFirstUseGreeting("general"),
            List.of("mcp_", "web_search", "calculator"),
            List.of(),
            List.of(),
            List.of(),
            defaultRoutingSettings(),
            List.of(
                "总结 MCP 风险系统的最新告警",
                "找出最近 7 天的异常账户变动",
                "列出核心 KPI 差距和改进建议"
            )
        );
    }

    private SkillRoutingSettings defaultRoutingSettings() {
        return new SkillRoutingSettings(true, true, 3);
    }

    private String defaultFirstUseGreeting(String skillId) {
        return switch (skillId == null ? "" : skillId.trim().toLowerCase(Locale.ROOT)) {
            case "risk" -> "欢迎使用风险分析技能。我会优先结合业务工具识别高风险点，并给出处置建议。";
            case "operations" -> "欢迎使用运营分析技能。我会结合指标与工具数据，帮你快速定位增长和转化问题。";
            case "report" -> "欢迎使用报告解读技能。我可以把复杂报告拆成可执行结论和会议摘要。";
            case "github_test" -> "欢迎使用 GitHub 测试技能。我会优先调用 GitHub 工具验证连接和仓库状态。";
            default -> "欢迎回来，我已准备好。你可以直接提问，或从快捷问题开始。";
        };
    }

    private Optional<SkillDefinition> builtinDefaultById(String skillId) {
        String id = skillId == null ? "" : skillId.trim().toLowerCase(Locale.ROOT);
        return defaultSkills().stream()
            .filter(skill -> skill.id().equals(id))
            .findFirst();
    }

    private String withRoutingPolicy(String basePrompt) {
        return appendRoutingPolicy(normalizeText(basePrompt));
    }

    private String appendRoutingPolicy(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return ROUTING_POLICY;
        }
        String trimmed = prompt.trim();
        if (trimmed.contains(ROUTING_POLICY_MARKER)) {
            return trimmed;
        }
        return trimmed + "\n\n" + ROUTING_POLICY;
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
            return candidates.stream().sorted().toList();
        }
        List<String> selected = candidates.stream()
            .filter(name -> normalizedPrefixes.stream().anyMatch(name::startsWith))
            .sorted()
            .toList();
        return selected.isEmpty() ? candidates.stream().sorted().toList() : selected;
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
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        List<String> quickQuestions,
        Long createdAt
    ) {
    }
}
