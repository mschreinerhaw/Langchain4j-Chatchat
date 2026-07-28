package com.chatchat.chat.skills;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles maintained Agent metadata into deterministic Runtime acceptance scenarios.
 *
 * <p>The catalog intentionally has no knowledge of Agent identifiers or business domains.
 * Scenario text is owned by the maintained Agent definition and remains the source of truth.</p>
 */
@Component
public class AgentScenarioCatalog {

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
