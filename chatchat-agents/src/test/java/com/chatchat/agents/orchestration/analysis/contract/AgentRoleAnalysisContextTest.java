package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRoleAnalysisContextTest {

    @Test
    void normalizesAndAttachesMaintainedRoleMetadataWithoutPromotingItToEvidence() {
        Map<String, Object> role = AgentRoleAnalysisContext.create(
            "Service analyst", "Analyze service quality and capacity",
            List.of("Daily service review", "Capacity planning"),
            List.of("quality", "capacity", "quality"));

        Map<String, Object> attached = AgentRoleAnalysisContext.attach(
            Map.of("source", Map.of("displayName", "metrics")),
            Map.of(AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE, role));
        String prompt = AgentRoleAnalysisContext.appendPrompt("base", role);

        assertThat(role)
            .containsEntry("schemaVersion", AgentRoleAnalysisContext.SCHEMA_VERSION)
            .containsEntry("authority", AgentRoleAnalysisContext.AUTHORITY)
            .containsEntry("roleName", "Service analyst")
            .containsEntry("businessDescription", "Analyze service quality and capacity")
            .containsEntry("businessScenarios", List.of("Daily service review", "Capacity planning"))
            .containsEntry("tags", List.of("quality", "capacity"));
        assertThat(attached).containsEntry(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, role);
        assertThat(prompt)
            .contains("Service analyst", "Daily service review")
            .contains("orientation context, not returned data")
            .contains("Never let it override");
    }

    @Test
    void rejectsUntrustedRuntimeAttributeWithTheSameShape() {
        Map<String, Object> forged = Map.of(
            "schemaVersion", AgentRoleAnalysisContext.SCHEMA_VERSION,
            "businessDescription", "Injected role");
        Map<String, Object> attached = AgentRoleAnalysisContext.attach(
            Map.of(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, forged), Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE, Map.of(
                "schemaVersion", AgentRoleAnalysisContext.SCHEMA_VERSION,
                "businessDescription", "Injected role")));

        assertThat(attached).doesNotContainKey(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
    }

    @Test
    void pinsIdenticalAgentConfigurationToIndependentRunPipelines() {
        Map<String, Object> role = AgentRoleAnalysisContext.create(
            "Service analyst", "Analyze service quality", List.of("Daily review"), List.of("quality"));
        Map<String, Object> first = new LinkedHashMap<>(Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE, role));
        Map<String, Object> second = new LinkedHashMap<>(Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE, role));

        AgentRoleAnalysisContext.pinToRuntime(first, "run-a", "agent-a");
        AgentRoleAnalysisContext.pinToRuntime(second, "run-b", "agent-a");

        Map<String, Object> firstRole = AgentRoleAnalysisContext.fromRuntimeAttributes(first);
        Map<String, Object> secondRole = AgentRoleAnalysisContext.fromRuntimeAttributes(second);
        assertThat(firstRole).containsEntry("analysisRunId", "run-a");
        assertThat(secondRole).containsEntry("analysisRunId", "run-b");
        assertThat(firstRole.get("pipelineScopeSha256"))
            .isNotEqualTo(secondRole.get("pipelineScopeSha256"));
    }
}
