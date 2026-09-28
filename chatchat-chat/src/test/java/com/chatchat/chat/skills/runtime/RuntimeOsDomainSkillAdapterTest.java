package com.chatchat.chat.skills.runtime;

import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRequirements;
import com.chatchat.runtime.skill.api.SkillResolution;
import com.chatchat.runtime.skill.api.SkillRouteResult;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.chatchat.runtime.skill.port.inbound.SkillRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeOsDomainSkillAdapterTest {
    @Test
    void resolvesFullInstructionsOnlyForMetadataCandidatesSelectedByTheRuntimeRouter() {
        SkillRouter router = mock(SkillRouter.class);
        SkillResolver resolver = mock(SkillResolver.class);
        SkillDescriptor descriptor = new SkillDescriptor("install", "v1", "Install", "Install software",
            "ops", "DATABASE", "", "", "", 0.9D, Map.of());
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()));
        when(resolver.resolve(any())).thenReturn(new SkillResolution(
            new ResolvedSkill(descriptor, "verified instructions", List.of(), SkillRequirements.empty(), Map.of()),
            new AuthorizedSkillScope(true, List.of("doc-1"), List.of(), List.of(), List.of(), List.of(), List.of()),
            "database", "RESOLVED", Map.of()));
        RuntimeOsDomainSkillAdapter adapter = new RuntimeOsDomainSkillAdapter(router, resolver);

        var result = adapter.retrievePublished("tenant", "user", List.of("role"),
            "install", List.of("install"));

        assertThat(result).singleElement().satisfies(skill -> {
            assertThat(skill.id()).isEqualTo("install");
            assertThat(skill.markdownContent()).isEqualTo("verified instructions");
        });
        ArgumentCaptor<com.chatchat.runtime.skill.api.SkillSearchRequest> search =
            ArgumentCaptor.forClass(com.chatchat.runtime.skill.api.SkillSearchRequest.class);
        verify(router).route(search.capture());
        assertThat(search.getValue().requestedSkillIds()).containsExactly("install");
        verify(resolver).resolve(any());
    }

    @Test
    void evidenceActivationDeterministicallyUsesAuthorizedRuntimeCandidates() {
        SkillRouter router = mock(SkillRouter.class);
        SkillResolver resolver = mock(SkillResolver.class);
        SkillDescriptor descriptor = new SkillDescriptor("install", "v1", "Install", "Install software",
            "ops", "DATABASE", "", "", "", 0.9D, Map.of());
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()));
        when(resolver.resolve(any())).thenReturn(new SkillResolution(
            new ResolvedSkill(descriptor, "verified instructions", List.of(), SkillRequirements.empty(), Map.of()),
            new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
            "database", "RESOLVED", Map.of()));
        RuntimeOsDomainSkillAdapter adapter = new RuntimeOsDomainSkillAdapter(router, resolver);

        var result = adapter.activateForEvidence("tenant", "user", List.of("role"), "install",
            List.of(new DomainSkillRuntimePort.EvidencePreview(
                "e1", "doc-1", "guide", "setup", "run setup")), 2);

        assertThat(result.activatedSkillIds()).containsExactly("install");
        assertThat(result.status()).isEqualTo("DETERMINISTIC_ROUTED");
        assertThat(result.compiledContext()).contains("verified instructions");
        verify(router).route(any());
        verify(resolver).resolve(any());
    }
}
