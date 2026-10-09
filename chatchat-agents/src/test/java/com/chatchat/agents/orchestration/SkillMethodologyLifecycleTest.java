package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SkillMethodologyLifecycleTest {
    @Test void appliedSnapshotOverridesEmptyLegacyRoutingInRuntimeEvents() throws Exception {
        var snapshot = SkillAnalysisContext.create("APPLIED", List.of(Map.of("id", "s", "name", "Method")),
            Map.of("PLAN", List.of("private instructions")));
        var events = record(snapshot, Map.of("schemaVersion", "domain_skill_planning.v2",
            "skills", List.of(), "activatedSkills", List.of()));
        assertThat(events).hasSize(3);
        assertThat(events.get(1).metadata()).containsEntry("domainSkillCount", 1)
            .containsEntry("activatedDomainSkillCount", 1).containsEntry("status", "APPLIED");
        assertThat(events.get(2).metadata()).containsEntry("applied", true)
            .containsEntry("eventState", "APPLIED").containsEntry("sourceCount", 0)
            .containsEntry("methodologyFingerprint", snapshot.get("fingerprint"));
        assertThat(new ObjectMapper().writeValueAsString(events)).doesNotContain("private instructions");
    }

    @Test void validEmptySnapshotOverridesStaleLegacyActivation() throws Exception {
        var snapshot = SkillAnalysisContext.create("NO_RELEVANT_SKILL", List.of(), Map.of());
        var events = record(snapshot, Map.of("schemaVersion", "domain_skill_planning.v2",
            "skills", List.of(Map.of("id", "old")), "activatedSkills", List.of(Map.of("id", "old"))));
        assertThat(events.get(2).metadata()).containsEntry("applied", false)
            .containsEntry("domainSkillCount", 0).containsEntry("eventState", "NOT_APPLIED");
        assertThat(events.get(2).content()).contains("未应用");
    }

    private List<AgentObservation> record(Map<String, Object> snapshot, Map<String, Object> legacy) throws Exception {
        var store = mock(AgentRunStore.class);
        var engine = new AgentOrchestrationEngine(mock(ChatModel.class), mock(ToolRegistry.class),
            mock(ToolRuntimeService.class), new ObjectMapper(), new ModelsConfig(), mock(EvidenceTrustEvaluator.class), store);
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("__agentRunId", "run");
        attributes.put(SkillAnalysisContext.ATTRIBUTE, snapshot);
        attributes.put(DomainSkillRuntimePort.PLANNING_CONTEXT_ATTRIBUTE, legacy);
        var method = AgentOrchestrationEngine.class.getDeclaredMethod("recordDomainKnowledgeCompilation", Map.class, Map.class);
        method.setAccessible(true);
        method.invoke(engine, attributes, new LinkedHashMap<String, Object>());
        var observations = ArgumentCaptor.forClass(AgentObservation.class);
        verify(store, times(3)).recordObservation(eq("run"), observations.capture());
        return observations.getAllValues();
    }
}
