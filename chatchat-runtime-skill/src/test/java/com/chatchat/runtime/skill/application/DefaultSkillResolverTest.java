package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRequirements;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import com.chatchat.runtime.skill.api.SkillSearchRequest;
import com.chatchat.runtime.skill.port.outbound.SkillPolicy;
import com.chatchat.runtime.skill.port.outbound.SkillSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSkillResolverTest {
    @Test
    void discardsLoadedInstructionsWhenDatabasePolicyDeniesTheSkill() {
        SkillDescriptor descriptor = new SkillDescriptor("risk", "v1", "Risk", "Risk analysis", "risk",
            "DATABASE", "", "", "", 1D, Map.of());
        SkillSource source = new SkillSource() {
            @Override public String sourceId() { return "database"; }
            @Override public List<SkillDescriptor> search(SkillSearchRequest request) { return List.of(descriptor); }
            @Override public Optional<ResolvedSkill> resolve(SkillResolutionRequest request) {
                return Optional.of(new ResolvedSkill(descriptor, "secret instructions", List.of(),
                    SkillRequirements.empty(), Map.of()));
            }
        };
        SkillPolicy deny = new SkillPolicy() {
            @Override public boolean canDiscover(SkillRoleContext context, SkillDescriptor value) { return false; }
            @Override public AuthorizedSkillScope authorize(SkillRoleContext context, ResolvedSkill skill) {
                return AuthorizedSkillScope.denied("SKILL_NOT_GRANTED");
            }
        };
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());

        var result = new DefaultSkillResolver(List.of(source), deny)
            .resolve(new SkillResolutionRequest("risk", "v1", role));

        assertThat(result.status()).isEqualTo("DENIED");
        assertThat(result.skill()).isNull();
        assertThat(result.authorizedScope().denialReasons()).containsExactly("SKILL_NOT_GRANTED");
    }
}
