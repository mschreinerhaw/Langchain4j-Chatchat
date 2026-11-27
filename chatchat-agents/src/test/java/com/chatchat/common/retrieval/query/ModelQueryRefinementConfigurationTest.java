package com.chatchat.common.retrieval.query;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.orchestration.model.DirectModelInvocation;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelQueryRefinementConfigurationTest {
    @Test void bindsTheAuthenticatedScopeBeforeAsyncRefinement() throws Exception {
        var model = mock(ChatModel.class);
        var factory = mock(ConfigurableChatModelFactory.class);
        var quota = mock(DirectModelInvocation.class);
        var scope = new KernelDataScope("tenant", "actor", "request", null, "run", null, Map.of());
        when(quota.authenticatedRequestScope()).thenReturn(scope);
        when(factory.create(eq("existing-model"), any(Duration.class), anyInt())).thenReturn(model);
        when(quota.chat(eq(scope), eq("query-refinement"), eq(model), any(ChatRequest.class), anyInt()))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"keywords\":[\"600519\"]}")).build());
        var beans = new StaticListableBeanFactory();
        beans.addBean("factory", factory);
        beans.addBean("quota", quota);
        var budget = new QueryOptimizationBudget();
        budget.setRefinementModel("existing-model");
        var refiner = new ModelQueryRefinementConfiguration().modelQueryRefiner(budget, mock(ModelResourceRegistry.class),
            new ObjectMapper(), beans.getBeanProvider(ConfigurableChatModelFactory.class), beans.getBeanProvider(DirectModelInvocation.class));
        var bound = refiner.forCaller();
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> bound.refine("600519", List.of(), 4, 250));
        assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).containsExactly("600519");
        verify(quota, times(1)).authenticatedRequestScope();
        verifyNoInteractions(model);
    }

    @Test void reusesRegisteredFactoryWithStrictCallLimitsAndVerbatimOutput() {
        var factory = mock(ConfigurableChatModelFactory.class); var model = mock(ChatModel.class);
        when(factory.create(eq("existing-model"), any(Duration.class), anyInt())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            assertThat(request.messages()).hasSize(2);
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"keywords\":[\"600519\",\"现金流\"]}")).build();
        });
        var beans = new StaticListableBeanFactory(); beans.addBean("factory", factory);
        var budget = new QueryOptimizationBudget(); budget.setRefinementModel("existing-model");
        var refiner = new ModelQueryRefinementConfiguration().modelQueryRefiner(budget, mock(ModelResourceRegistry.class),
            new ObjectMapper(), beans.getBeanProvider(ConfigurableChatModelFactory.class), beans.getBeanProvider(DirectModelInvocation.class));
        assertThat(refiner.refine("分析600519现金流", List.of("600519"), 4, 250)).containsExactly("600519", "现金流");
        verify(factory).create("existing-model", Duration.ofMillis(250), budget.getRefinementMaxOutputTokens());
    }
    @Test void hallucinatedModelEntitiesAreRejected() {
        var factory = mock(ConfigurableChatModelFactory.class); var model = mock(ChatModel.class);
        when(factory.create(anyString(), any(Duration.class), anyInt())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from("{\"keywords\":[\"unrelated company\"]}")).build());
        var beans = new StaticListableBeanFactory(); beans.addBean("factory", factory);
        var budget = new QueryOptimizationBudget(); budget.setRefinementModel("existing-model");
        var refiner = new ModelQueryRefinementConfiguration().modelQueryRefiner(budget, mock(ModelResourceRegistry.class),
            new ObjectMapper(), beans.getBeanProvider(ConfigurableChatModelFactory.class), beans.getBeanProvider(DirectModelInvocation.class));
        assertThatThrownBy(() -> refiner.refine("600519", List.of("600519"), 4, 250))
            .hasMessageContaining("QUERY_REFINEMENT_INVALID");
    }
}
