package com.chatchat.chat.skills.runtime;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdkAnalysisSkillSourceTest {
    final SkillResolver resolver = mock(SkillResolver.class);
    final SkillDescriptor descriptor = new SkillDescriptor("opaque", "v1", "Method", "Description", "", "DB", "", "", "", 1, Map.of());
    final AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    final AdkAnalysisSkillSource source = new AdkAnalysisSkillSource(List.of(descriptor), resolver,
        new SkillRoleContext("t", "u", List.of(), List.of(), Map.of("agentId", "a")));
    final String alias = AdkAnalysisSkillSource.alias("opaque");
    void resolve(String body) {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(new ResolvedSkill(descriptor, body, List.of(), null, Map.of()), scope, "db", "RESOLVED", Map.of()));
    }
    @Test void metadataDiscoveryDoesNotLoadBodies() {
        assertThat(source.listFrontmatters().blockingGet()).containsKey(alias);
        verifyNoInteractions(resolver);
    }
    @Test void unknownAliasNeverReachesResolver() {
        assertThatThrownBy(() -> source.loadInstructions("other").blockingGet()).hasMessageContaining("unavailable");
        verifyNoInteractions(resolver);
    }
    @Test void deniedBodyCannotBeLoaded() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(null, null, "db", "DENIED", Map.of()));
        assertThatThrownBy(() -> source.loadInstructions(alias).blockingGet()).hasMessageContaining("unavailable");
    }
    @Test void changedBodyCannotPublishOldMethodology() {
        resolve("old"); source.loadInstructions(alias).blockingGet(); resolve("new");
        assertThatThrownBy(() -> source.applied(List.of(alias))).hasMessageContaining("unavailable");
    }
    @Test void oversizedBodyDoesNotSilentlyTruncateMethods() {
        resolve("x".repeat(72001));
        assertThatThrownBy(() -> source.loadInstructions(alias).blockingGet()).hasMessageContaining("budget");
        assertThatThrownBy(() -> source.applied(List.of(alias))).hasMessageContaining("unavailable");
    }
    @Test void scriptResourcesAreUnavailable() {
        resolve("method"); source.loadInstructions(alias).blockingGet();
        assertThatThrownBy(() -> source.loadResource(alias, "scripts/run.py").blockingGet()).hasMessageContaining("unavailable");
        verify(resolver, never()).readResource(any());
    }
}
