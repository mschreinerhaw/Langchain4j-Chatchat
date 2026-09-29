package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.runtime.skill.api.agent.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleAdkSkillRuntimeAdapterTest {
    private final SkillResolver resolver = mock(SkillResolver.class);
    private final ConfigurableChatModelFactory models = mock(ConfigurableChatModelFactory.class);
    private final SkillDescriptor descriptor = new SkillDescriptor("skill-1", "v1", "Analysis", "Analyze", "finance", "DATABASE", "", "", "", 1, Map.of());
    private final ResolvedSkill skill = new ResolvedSkill(descriptor, "LATEST_BODY_FOR_ANALYSIS", List.of(), null, Map.of());
    private final AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private RuntimeAgentExecutionRequest request() {
        return new RuntimeAgentExecutionRequest("GOOGLE_ADK_NATIVE", "Analyze", new SkillRoleContext("t", "u", List.of("r"), List.of(), Map.of("agentId", "a")),
            skill, scope, null, Map.of("modelName", "test", "timeoutMs", 5000));
    }
    @Test void runsRealAdkToolLoopUsingAuthorizedLatestSkillBody() {
        var request = request();
        when(resolver.resolve(any())).thenReturn(new SkillResolution(skill, scope, "db", "RESOLVED", Map.of()));
        var model = mock(ChatModel.class);
        when(models.create("test")).thenReturn(model);
        String alias = new AdkAuthorizedSkillSource(request, resolver).alias;
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("load-1").name("load_skill").arguments("{\"skill_name\":\"" + alias + "\"}").build())).build())
            .thenAnswer(call -> {
                ChatRequest second = call.getArgument(0);
                assertThat(second.messages().toString()).contains("LATEST_BODY_FOR_ANALYSIS");
                assertThat(second.toolSpecifications()).extracting(tool -> tool.name())
                    .contains("load_skill").doesNotContain("run_skill_script");
                return ChatResponse.builder().aiMessage(AiMessage.from("Evidence-limited analysis")).build();
            });
        var result = new GoogleAdkSkillRuntimeAdapter(models, resolver, new ObjectMapper()).execute(request);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.output()).contains("Evidence-limited");
        assertThat(result.metadata()).containsEntry("adapterId", "google-adk-native-skills");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }
    @Test void sourceRejectsOtherSkillsAndRechecksAuthorization() {
        var source = new AdkAuthorizedSkillSource(request(), resolver);
        assertThatThrownBy(() -> source.loadInstructions("another-skill").blockingGet()).hasMessageContaining("unavailable");
        verifyNoInteractions(resolver);
        when(resolver.resolve(any())).thenReturn(new SkillResolution(null, null, "db", "DENIED", Map.of()));
        assertThatThrownBy(() -> source.loadInstructions(source.alias).blockingGet()).hasMessageContaining("unavailable");
    }
    @Test void doesNotClaimSkillAppliedWhenModelSkipsLoadingInstructions() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(skill, scope, "db", "RESOLVED", Map.of()));
        var model = mock(ChatModel.class); when(models.create("test")).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Generic answer")).build());
        var adapter = new GoogleAdkSkillRuntimeAdapter(models, resolver, new ObjectMapper());
        try {
            var result = adapter.execute(request());
            assertThat(result.status()).isEqualTo("SKILL_NOT_APPLIED");
            assertThat(result.metadata()).containsEntry("skillLoaded", false);
        } finally { adapter.close(); }
    }
}
