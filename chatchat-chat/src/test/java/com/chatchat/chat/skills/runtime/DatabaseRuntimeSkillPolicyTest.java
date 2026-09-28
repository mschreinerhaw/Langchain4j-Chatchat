package com.chatchat.chat.skills.runtime;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRequirements;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRuntimeSkillPolicyTest {
    @Test
    void grantsOnlyDatabaseBoundResourcesThatAlsoHaveRoleGrants() {
        ResourceAuthorizationPort authorization = mock(ResourceAuthorizationPort.class);
        SkillResourceScopeRepository scopes = mock(SkillResourceScopeRepository.class);
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role-1"), List.of(), Map.of());
        SkillDescriptor descriptor = new SkillDescriptor("skill-1", "v1", "Install", "", "ops",
            "DATABASE", "", "", "", 1D, Map.of());
        ResolvedSkill skill = new ResolvedSkill(descriptor, "instructions", List.of(),
            new SkillRequirements(List.of("doc-1", "doc-from-markdown"), List.of(),
                List.of("tool-from-markdown"), List.of(), List.of()), Map.of());
        when(authorization.explicitlyAllowedIds(ResourceAuthorizationPort.SKILL, "tenant", "user",
            Set.of("role-1"), Set.of("skill-1"))).thenReturn(Set.of("skill-1"));
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant", "skill-1"))
            .thenReturn(List.of(binding("DOCUMENT", "doc-1"), binding("DOCUMENT", "doc-ungranted")));
        when(authorization.explicitlyAllowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant", "user",
            Set.of("role-1"), Set.of("doc-1"))).thenReturn(Set.of("doc-1"));

        var result = new DatabaseRuntimeSkillPolicy(authorization, scopes).authorize(role, skill);

        assertThat(result.skillAllowed()).isTrue();
        assertThat(result.documentIds()).containsExactly("doc-1");
        assertThat(result.mcpToolIds()).isEmpty();
        assertThat(result.documentIds()).doesNotContain("doc-from-markdown", "doc-ungranted");
    }

    private SkillResourceScope binding(String type, String id) {
        SkillResourceScope value = new SkillResourceScope();
        value.setResourceType(type);
        value.setResourceId(id);
        value.setEnabled(true);
        return value;
    }
}
