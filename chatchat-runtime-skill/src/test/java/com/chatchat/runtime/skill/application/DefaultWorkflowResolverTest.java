package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRequirements;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowResolverTest {
    @Test
    void resolvesOnlyAnExplicitDatabaseAuthorizedWorkflowAndPublishedType() {
        var resolver = new DefaultWorkflowResolver();
        var result = resolver.resolve(skill(), scope(List.of("document-install")), role(),
            Map.of("workflowId", "document-install", "workflowType", "DOCUMENT"));

        assertThat(result.resolved()).isTrue();
        assertThat(result.workflow().workflowId()).isEqualTo("document-install");
        assertThat(result.workflow().type().name()).isEqualTo("DOCUMENT");
    }

    @Test
    void rejectsAnInventedWorkflowInsteadOfFallingBack() {
        var result = new DefaultWorkflowResolver().resolve(skill(), scope(List.of("document-install")), role(),
            Map.of("workflowId", "model-invented", "workflowType", "DOCUMENT"));
        assertThat(result.resolved()).isFalse();
        assertThat(result.status()).isEqualTo("WORKFLOW_NOT_AUTHORIZED");
    }

    private ResolvedSkill skill() {
        return new ResolvedSkill(new SkillDescriptor("install", "v1", "Install", "", "ops", "DB",
            "", "", "", 1, Map.of()), "instructions", List.of(), SkillRequirements.empty(), Map.of());
    }
    private AuthorizedSkillScope scope(List<String> workflows) {
        return new AuthorizedSkillScope(true, List.of("doc-1"), List.of(), List.of("search"),
            List.of(), workflows, List.of());
    }
    private SkillRoleContext role() {
        return new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
    }
}
