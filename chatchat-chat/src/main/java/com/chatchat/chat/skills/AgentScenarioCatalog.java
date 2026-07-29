package com.chatchat.chat.skills;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles maintained Agent metadata into deterministic Runtime acceptance scenarios.
 *
 * <p>The catalog intentionally has no knowledge of Agent identifiers or business domains.
 * Scenario text is owned by the maintained Agent definition and remains the source of truth.</p>
 */
@Component
public class AgentScenarioCatalog {

    /**
     * Compiles all published Agent definitions and applies production-level suite checks.
     */
    public CoverageSuite compilePublished(Collection<SkillDefinition> maintainedAgents) {
        List<SkillDefinition> publishedAgents = maintainedAgents == null
            ? List.of()
            : maintainedAgents.stream()
                .filter(java.util.Objects::nonNull)
                .filter(agent -> SkillCatalogService.MARKET_STATUS_PUBLISHED.equalsIgnoreCase(text(agent.marketStatus())))
                .toList();
        CoverageSuite compiled = compile(publishedAgents);
        List<CoverageIssue> issues = new ArrayList<>(compiled.issues());
        long defaultAgentCount = publishedAgents.stream()
            .filter(agent -> Boolean.TRUE.equals(agent.defaultAgent()))
            .count();
        if (publishedAgents.isEmpty()) {
            issues.add(new CoverageIssue(null, "PUBLISHED_AGENT_MISSING",
                "At least one published Agent is required"));
        } else if (defaultAgentCount == 0) {
            issues.add(new CoverageIssue(null, "DEFAULT_AGENT_MISSING",
                "Exactly one published Agent must be the default"));
        } else if (defaultAgentCount > 1) {
            issues.add(new CoverageIssue(null, "DEFAULT_AGENT_DUPLICATED",
                "Only one published Agent may be the default"));
        }
        return new CoverageSuite(compiled.agents(), issues, compiled.scenarioCount());
    }

    public CoverageSuite compile(Collection<SkillDefinition> maintainedAgents) {
        List<SkillDefinition> agents = maintainedAgents == null
            ? List.of()
            : maintainedAgents.stream().filter(java.util.Objects::nonNull).toList();
        Map<String, SkillDefinition> uniqueAgents = new LinkedHashMap<>();
        List<CoverageIssue> issues = new ArrayList<>();
        for (SkillDefinition agent : agents) {
            String agentId = text(agent.id());
            if (agentId == null) {
                issues.add(new CoverageIssue(null, "AGENT_ID_MISSING", "Agent id is required"));
                continue;
            }
            if (uniqueAgents.putIfAbsent(agentId.toLowerCase(Locale.ROOT), agent) != null) {
                issues.add(new CoverageIssue(agentId, "AGENT_ID_DUPLICATED", "Agent id must be unique"));
            }
        }

        List<AgentCoverage> coverage = uniqueAgents.values().stream()
            .map(agent -> compileAgent(agent, issues))
            .toList();
        return new CoverageSuite(
            List.copyOf(coverage),
            List.copyOf(issues),
            coverage.stream().mapToInt(item -> item.scenarios().size()).sum()
        );
    }

    private AgentCoverage compileAgent(SkillDefinition agent, List<CoverageIssue> issues) {
        String agentId = text(agent.id());
        if (text(agent.label()) == null) {
            issues.add(new CoverageIssue(agentId, "AGENT_LABEL_MISSING", "Agent label is required"));
        }
        if (text(agent.defaultMode()) == null) {
            issues.add(new CoverageIssue(agentId, "AGENT_DEFAULT_MODE_MISSING", "Agent default mode is required"));
        }
        validateToolConfiguration(agent, issues);
        validateWorkflow(agent, issues);
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        addScenarios(scenarios, agent.quickQuestions(), "quick_question");
        addScenarios(scenarios, agent.usageScenarios(), "usage_scenario");
        if (scenarios.isEmpty()) {
            addScenario(scenarios, agent.description(), "description");
        }
        if (scenarios.isEmpty()) {
            addScenario(scenarios, agent.label(), "label");
        }
        if (scenarios.isEmpty()) {
            issues.add(new CoverageIssue(
                agentId,
                "AGENT_SCENARIO_MISSING",
                "Agent must maintain a quick question, usage scenario, description, or label"
            ));
        }
        return new AgentCoverage(
            agentId,
            text(agent.defaultMode()),
            Boolean.TRUE.equals(agent.defaultAgent()),
            List.copyOf(scenarios.values())
        );
    }

    private void validateToolConfiguration(SkillDefinition agent, List<CoverageIssue> issues) {
        String agentId = text(agent.id());
        Map<String, SkillToolConfig> toolConfigs = new LinkedHashMap<>();
        if (agent.toolConfigs() != null) {
            for (SkillToolConfig config : agent.toolConfigs()) {
                if (config == null) {
                    continue;
                }
                String toolName = text(config.toolName());
                if (toolName == null) {
                    issues.add(new CoverageIssue(agentId, "TOOL_CONFIG_NAME_MISSING",
                        "Every Agent tool configuration must declare a tool name"));
                    continue;
                }
                String key = toolName.toLowerCase(Locale.ROOT);
                if (toolConfigs.putIfAbsent(key, config) != null) {
                    issues.add(new CoverageIssue(agentId, "TOOL_CONFIG_DUPLICATED",
                        "Agent tool configuration is duplicated: " + toolName));
                }
            }
        }
        for (String boundTool : normalizedNames(agent.boundMcpToolNames())) {
            SkillToolConfig config = toolConfigs.get(boundTool.toLowerCase(Locale.ROOT));
            if (config != null && Boolean.FALSE.equals(config.enabled())) {
                issues.add(new CoverageIssue(agentId, "BOUND_TOOL_DISABLED",
                    "Bound Agent tool is disabled by tool configuration: " + boundTool));
            }
        }
    }

    private void validateWorkflow(SkillDefinition agent, List<CoverageIssue> issues) {
        if (agent.workflowConfig() != null
            && Boolean.FALSE.equals(agent.workflowConfig().get("enabled"))) {
            return;
        }
        List<Map<String, Object>> steps = workflowSteps(agent.workflowConfig());
        if (steps.isEmpty()) {
            return;
        }
        String agentId = text(agent.id());
        Set<String> callableTools = callableToolNames(agent);
        Set<String> workflowTools = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            String toolName = firstText(step.get("tool"), step.get("toolName"));
            if (toolName == null) {
                issues.add(new CoverageIssue(agentId, "WORKFLOW_STEP_TOOL_MISSING",
                    "Every workflow step must declare a tool"));
                continue;
            }
            String normalizedTool = toolName.toLowerCase(Locale.ROOT);
            if (!workflowTools.add(normalizedTool)) {
                issues.add(new CoverageIssue(agentId, "WORKFLOW_TOOL_DUPLICATED",
                    "Workflow tool is configured more than once: " + toolName));
            }
            if (!callableTools.contains(normalizedTool)) {
                String requirement = required(step) ? "Required" : "Optional";
                issues.add(new CoverageIssue(agentId, "WORKFLOW_TOOL_NOT_BOUND",
                    requirement + " workflow tool is not enabled and bound to the Agent: " + toolName));
            }
        }
        validateWorkflowDependencies(agentId, agent.workflowConfig(), workflowTools, issues);
    }

    private void validateWorkflowDependencies(String agentId,
                                              Map<String, Object> workflowConfig,
                                              Set<String> workflowTools,
                                              List<CoverageIssue> issues) {
        if (workflowConfig == null) {
            return;
        }
        Object dependencies = firstObject(
            workflowConfig.get("toolDependencies"),
            workflowConfig.get("tool_dependencies")
        );
        if (!(dependencies instanceof Map<?, ?> dependencyMap)) {
            return;
        }
        dependencyMap.forEach((tool, value) -> {
            String toolName = text(String.valueOf(tool));
            if (toolName != null && !workflowTools.contains(toolName.toLowerCase(Locale.ROOT))) {
                issues.add(new CoverageIssue(agentId, "WORKFLOW_DEPENDENCY_TOOL_UNKNOWN",
                    "Workflow dependency target is not declared as a step: " + toolName));
            }
            if (!(value instanceof Map<?, ?> spec)) {
                return;
            }
            Object dependsOn = firstObject(spec.get("dependsOn"), spec.get("depends_on"));
            if (!(dependsOn instanceof Collection<?> values)) {
                return;
            }
            for (Object dependency : values) {
                String dependencyName = text(dependency == null ? null : String.valueOf(dependency));
                if (dependencyName != null
                    && !workflowTools.contains(dependencyName.toLowerCase(Locale.ROOT))) {
                    issues.add(new CoverageIssue(agentId, "WORKFLOW_DEPENDENCY_UNKNOWN",
                        "Workflow dependency is not declared as a step: " + dependencyName));
                }
            }
        });
    }

    private Set<String> callableToolNames(SkillDefinition agent) {
        Set<String> names = new LinkedHashSet<>();
        normalizedNames(agent.boundMcpToolNames()).stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .forEach(names::add);
        if (agent.toolConfigs() != null) {
            agent.toolConfigs().stream()
                .filter(java.util.Objects::nonNull)
                .filter(config -> !Boolean.FALSE.equals(config.enabled()))
                .map(SkillToolConfig::toolName)
                .map(this::text)
                .filter(java.util.Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .forEach(names::add);
        }
        return names;
    }

    private List<String> normalizedNames(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(this::text)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<Map<String, Object>> workflowSteps(Map<String, Object> workflowConfig) {
        if (workflowConfig == null || workflowConfig.isEmpty()) {
            return List.of();
        }
        Object workflow = firstObject(workflowConfig.get("mcpWorkflow"), workflowConfig.get("steps"));
        if (workflow == null) {
            workflow = workflowConfig;
        }
        if (workflow instanceof List<?> list) {
            return maps(list);
        }
        if (workflow instanceof Map<?, ?> map) {
            Object nested = firstObject(map.get("mcpWorkflow"), map.get("steps"));
            if (nested instanceof List<?> list) {
                return maps(list);
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> maps(List<?> values) {
        return values.stream()
            .filter(Map.class::isInstance)
            .map(value -> {
                Map<?, ?> map = (Map<?, ?>) value;
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(String.valueOf(key), item));
                return copy;
            })
            .toList();
    }

    private boolean required(Map<String, Object> step) {
        Object value = step.get("required");
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value == null ? null : String.valueOf(value));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Object firstObject(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void addScenarios(Map<String, Scenario> scenarios, List<String> values, String source) {
        if (values == null) {
            return;
        }
        values.forEach(value -> addScenario(scenarios, value, source));
    }

    private void addScenario(Map<String, Scenario> scenarios, String value, String source) {
        String query = text(value);
        if (query == null) {
            return;
        }
        scenarios.putIfAbsent(query.toLowerCase(Locale.ROOT), new Scenario(source, query));
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CoverageSuite(
        List<AgentCoverage> agents,
        List<CoverageIssue> issues,
        int scenarioCount
    ) {
        public CoverageSuite {
            agents = agents == null ? List.of() : List.copyOf(agents);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record AgentCoverage(
        String agentId,
        String defaultMode,
        boolean defaultAgent,
        List<Scenario> scenarios
    ) {
        public AgentCoverage {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    public record Scenario(String source, String query) {
    }

    public record CoverageIssue(String agentId, String code, String message) {
    }
}
