package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import com.chatchat.runtime.skill.api.SkillSearchRequest;
import com.chatchat.runtime.skill.port.outbound.SkillPolicy;
import com.chatchat.runtime.skill.port.outbound.SkillSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSkillRouterTest {
    @Test
    void routesAuthorizedMetadataWithoutLoadingSkillInstructions() {
        AtomicInteger resolutions = new AtomicInteger();
        SkillSource source = new SkillSource() {
            @Override public String sourceId() { return "database"; }
            @Override public List<SkillDescriptor> search(SkillSearchRequest request) {
                return List.of(descriptor("allowed", 0.8D), descriptor("denied", 0.9D));
            }
            @Override public Optional<ResolvedSkill> resolve(SkillResolutionRequest request) {
                resolutions.incrementAndGet();
                return Optional.empty();
            }
        };
        SkillPolicy policy = new SkillPolicy() {
            @Override public boolean canDiscover(SkillRoleContext context, SkillDescriptor descriptor) {
                return "allowed".equals(descriptor.id());
            }
            @Override public AuthorizedSkillScope authorize(SkillRoleContext context, ResolvedSkill skill) {
                return AuthorizedSkillScope.denied("not used");
            }
        };
        var request = new SkillSearchRequest("install", context(), List.of(), 5, Map.of());

        var result = new DefaultSkillRouter(List.of(source), policy).route(request);

        assertThat(result.status()).isEqualTo("ROUTED");
        assertThat(result.candidates()).extracting(SkillDescriptor::id).containsExactly("allowed");
        assertThat(result.diagnostics()).containsEntry("contentLoaded", false)
            .containsEntry("authorizationApplied", true);
        assertThat(resolutions).hasValue(0);
    }

    private SkillDescriptor descriptor(String id, double score) {
        return new SkillDescriptor(id, "v1", id, id + " description", "ops",
            "DATABASE", "", "", "", score, Map.of());
    }

    private SkillRoleContext context() {
        return new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
    }
}
