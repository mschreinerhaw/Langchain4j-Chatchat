package com.chatchat.common.knowledge;

import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeContractsTest {

    @Test
    void rejectsDynamicSkillPlansThatExceedTheRuntimeBudget() {
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "rules", KnowledgeSkillType.RULE_LOOKUP, "securities.risk", "find rules",
            List.of("concentration"), 1, 600, Map.of());

        assertThatThrownBy(() -> new KnowledgeSkillPlan("ignored", "RISK", List.of(skill), 500))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceed");
    }

    @Test
    void clampsKnowledgeRequestToThePlatformHardLimitAndCopiesScope() {
        KnowledgeRequest request = new KnowledgeRequest(
            "ignored", "analyze risk", "RISK", 100_000,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc-1"), List.of(), List.of()),
            Set.of(KnowledgeSkillType.RULE_LOOKUP), Map.of());

        // The public contract follows the platform limit; continuation may raise that limit
        // without changing request clamping semantics.
        assertThat(request.maxTokens()).isEqualTo(KnowledgeRequest.HARD_MAX_TOKENS);
        assertThat(request.scope().documentIds()).containsExactly("doc-1");
        assertThat(request.schemaVersion()).isEqualTo(KnowledgeRequest.SCHEMA_VERSION);
    }

    @Test
    void runtimeContextCannotClaimMoreTokensThanItsBudget() {
        assertThatThrownBy(() -> new KnowledgeContext(
            "ignored", null, List.of(), "too much", List.of(), 11, 10, true, "used"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds");
    }

    @Test
    void exposesTruncationAsContinuationControlInsteadOfAnInformationalHint() {
        KnowledgeContext context = new KnowledgeContext(
            "ignored", null, List.of(), "partial", List.of(), 1, 10, true, "used");

        assertThat(context.toRuntimeProjection())
            .containsEntry("completionState", "EXPANSION_REQUIRED")
            .containsEntry("continuationRequired", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) context.toRuntimeProjection().get("usageContract");
        assertThat(contract).containsEntry("directFinalAllowed", false);
    }
}
