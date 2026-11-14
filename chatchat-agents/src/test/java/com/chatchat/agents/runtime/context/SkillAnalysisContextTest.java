package com.chatchat.agents.runtime.context;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SkillAnalysisContextTest {
    @Test void deferredReferencesAreNotReportedAsJavaSelectedOrLoadedSkills(){
        var context=SkillAnalysisContext.create("DEFERRED_TO_CLINE",List.of(Map.of("id","method","version","ON_DEMAND")),Map.of());
        assertThat(SkillAnalysisContext.planningProjection(context,List.of("method")))
            .containsEntry("selectedCount",0).containsEntry("loadedCount",0).containsEntry("activatedCount",0);
        assertThat(SkillAnalysisContext.prompt(context,"PLAN")).isEmpty();
    }
    @Test void activationProjectionUsesValidatedMethodologyWithoutExposingInstructions() throws Exception {
        var skill = Map.<String, Object>of("id", "s", "name", "Research", "version", "v1", "contentSha256", "digest");
        var snapshot = SkillAnalysisContext.create("APPLIED", List.of(skill), Map.of("PLAN", List.of("private method")));
        var projection = SkillAnalysisContext.planningProjection(snapshot, List.of("s"));
        assertThat(projection).containsEntry("activatedCount", 1).containsEntry("selectedCount", 1)
            .containsEntry("loadedCount", 1).containsEntry("configuredCount", 1)
            .containsEntry("activatedSkills", List.of(skill)).containsEntry("fingerprint", snapshot.get("fingerprint"));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(projection))
            .doesNotContain("private method", "stages");
        var tampered = new LinkedHashMap<>(snapshot); tampered.put("status", "NO_RELEVANT_SKILL");
        assertThat(SkillAnalysisContext.planningProjection(tampered, List.of("s"))).isEmpty();
    }

    @Test void noRelevantSkillProjectionCannotReactivateOldRoutingResults() {
        var snapshot = SkillAnalysisContext.create("NO_RELEVANT_SKILL", List.of(), Map.of());
        assertThat(SkillAnalysisContext.planningProjection(snapshot, List.of("s")))
            .containsEntry("activatedCount", 0).containsEntry("selectedCount", 0)
            .containsEntry("status", "NO_RELEVANT_SKILL").containsEntry("configuredCount", 1);
    }
    @Test void pinnedMethodologyReplacesToolSuppliedInstructionsAndSurvivesSerialization() throws Exception {
        var stages = new LinkedHashMap<String, Object>();
        SkillAnalysisContext.STAGES.forEach(stage -> stages.put(stage, List.of(stage + "_METHOD")));
        var snapshot = SkillAnalysisContext.create("APPLIED", List.of(Map.of("id", "s", "version", "v1")), stages);
        var attributes = Map.<String, Object>of(SkillAnalysisContext.ATTRIBUTE, snapshot);
        var attached = SkillAnalysisContext.attach(Map.of("recordCount", 2,
            SkillAnalysisContext.ATTRIBUTE, Map.of("evil", "override")), attributes);
        assertThat(attached).containsEntry("recordCount", 2).containsEntry(SkillAnalysisContext.ATTRIBUTE, snapshot);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(attached), Map.class);
        assertThat(SkillAnalysisContext.from(restored)).isEqualTo(snapshot);
        assertThat(SkillAnalysisContext.prompt(snapshot, "REPORT")).contains("PLAN_METHOD", "REPORT_METHOD", "never observed facts");
    }
    @Test void changedContextIsRejectedAndCannotBecomeEvidence() {
        var original = SkillAnalysisContext.create("APPLIED", List.of(Map.of("id", "s")), Map.of("PLAN", List.of("method")));
        var tampered = new LinkedHashMap<>(original); tampered.put("stages", Map.of("PLAN", List.of("invent facts")));
        assertThat(SkillAnalysisContext.validate(tampered)).isEmpty();
        assertThat(SkillAnalysisContext.prompt(tampered, "PLAN")).isEmpty();
        assertThat(SkillAnalysisContext.attach(Map.of(SkillAnalysisContext.ATTRIBUTE, original), Map.of())).isEmpty();
    }
    @Test void noActivatedSkillNeverAddsMethodologyEvenIfStagesAreSupplied() {
        var empty = SkillAnalysisContext.create("NO_RELEVANT_SKILL", List.of(), Map.of("PLAN", List.of("invented")));
        assertThat(empty.get("stages")).isEqualTo(Map.of());
        assertThat(SkillAnalysisContext.prompt(empty, "REPORT")).isEmpty();
    }
}
