package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.agent.*;
import com.chatchat.runtime.skill.api.discovery.*;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.identity.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.resource.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.*;
import com.chatchat.runtime.skill.port.outbound.SkillIntentPlanner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SkillIntelligenceLayerTest {
    private SkillCompositionRequest request(int limit) {
        return new SkillCompositionRequest("Analyze", new SkillRoleContext("t", "u", List.of(), List.of(), Map.of()),
            List.of("finance.summary"), List.of(), Map.of(), Map.of(), "GOOGLE_ADK_NATIVE", Map.of(), limit);
    }
    private SkillDescriptor descriptor(String id) {
        return new SkillDescriptor(id, "v1", id, "", "finance", "DATABASE", "", "", "", 1,
            Map.of("capabilities", List.of("finance.summary")));
    }
    private SkillIntelligenceLayer layer(List<SkillDescriptor> catalog, SkillRuntime execution) {
        SkillRouter router = request -> new SkillRouteResult(catalog.stream().filter(item -> request.requestedSkillIds().isEmpty()
            || request.requestedSkillIds().contains(item.id())).toList(), "ROUTED", Map.of());
        SkillResolver resolver = new SkillResolver() {
            public SkillResolution resolve(SkillResolutionRequest request) {
                var item = catalog.stream().filter(skill -> skill.id().equals(request.skillId())).findFirst().orElseThrow();
                return new SkillResolution(new ResolvedSkill(item, "body", List.of(), null, Map.of()),
                    new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of("fixed"), List.of()), "db", "RESOLVED", Map.of());
            }
            public Optional<SkillResourceContent> readResource(SkillResourceRequest request) { return Optional.empty(); }
        };
        SkillIntentPlanner planner = (request, candidates) -> new SkillIntentPlanner.Intent("finance", "Analyze",
            List.of(new SkillIntentPlanner.Task("Summary", List.of("finance.summary"))), "TEST");
        return new SkillIntelligenceLayer(router, planner, new SkillCompositionRuntime(router, resolver, new DefaultWorkflowResolver(), execution), execution);
    }
    private SkillExecutionResult result(SkillDataResult.Status status) {
        var requirement = new SkillDataRequirement("rows", "finance.rows.v1", List.of(), false, Map.of());
        return new SkillExecutionResult("COMPLETED", null, null, null, new RuntimeAgentExecutionResult("COMPLETED", "analysis", Map.of()),
            Map.of(SkillDataAcquisition.RESULTS, List.of(new SkillDataResult(requirement, status, List.of(Map.of("value", 1)),
                Map.of("evidenceId", "e1"), List.of()))));
    }
    @Test void supplementsFailedSkillAndPreservesPartialResultWithoutRepeatingIt() {
        var calls = new ArrayList<String>();
        var layer = layer(List.of(descriptor("a"), descriptor("b")), request -> {
            var id = request.requestedSkillIds().get(0); calls.add(id);
            return result(id.equals("a") ? SkillDataResult.Status.FAILED : SkillDataResult.Status.AVAILABLE);
        });
        var result = layer.execute(request(4));
        assertThat(calls).containsExactly("a", "b");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.results()).containsKeys("a", "b");
        assertThat(result.events()).extracting(SkillIntelligenceLayer.Event::stage).contains("SUPPLEMENT_PLANNED");
    }
    @Test void missingInputStopsWithoutTryingOtherSkills() {
        var calls = new ArrayList<String>();
        var result = layer(List.of(descriptor("a"), descriptor("b")), request -> {
            calls.add(request.requestedSkillIds().get(0)); return result(SkillDataResult.Status.MISSING_INPUT);
        }).execute(request(4));
        assertThat(calls).containsExactly("a");
        assertThat(result.stopReason()).isEqualTo("USER_INPUT_REQUIRED");
        assertThat(result.missingCapabilities()).contains("finance.summary");
    }
    @Test void enforcesGlobalSkillBudgetAndDoesNotTreatEmptyEvidenceAsSuccess() {
        var calls = new ArrayList<String>();
        var result = layer(List.of(descriptor("a"), descriptor("b")), request -> {
            calls.add(request.requestedSkillIds().get(0)); return result(SkillDataResult.Status.EMPTY);
        }).execute(request(1));
        assertThat(calls).containsExactly("a");
        assertThat(result.stopReason()).isEqualTo("SKILL_BUDGET");
        assertThat(result.status()).isEqualTo("COMPLETED_WITH_LIMITATIONS");
    }
    @Test void noAuthorizedCandidatesNeverExecutes() {
        var result = layer(List.of(), request -> { throw new AssertionError("must not execute"); }).execute(request(4));
        assertThat(result.results()).isEmpty();
        assertThat(result.status()).isEqualTo("COMPLETED_WITH_LIMITATIONS");
    }
}
