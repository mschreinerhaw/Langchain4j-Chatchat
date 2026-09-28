package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class DefaultAgentRuntimeDispatcherTest {
    @Test
    void failsClosedWhenAnEngineAdapterIsNotRegistered() {
        var request = new RuntimeAgentExecutionRequest("GOOGLE_ADK", "query", null,
            null, null, null, Map.of());
        var result = new DefaultAgentRuntimeDispatcher(List.of()).execute(request);
        assertThat(result.status()).isEqualTo("ENGINE_NOT_REGISTERED");
    }

    @Test
    void dispatchesOnlyToTheExactEngineAdapter() {
        AgentRuntimeAdapter adapter = new AgentRuntimeAdapter() {
            @Override public String adapterId() { return "google-adk"; }
            @Override public boolean supports(String engine) { return "GOOGLE_ADK".equals(engine); }
            @Override public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
                return new RuntimeAgentExecutionResult("COMPLETED", "ok", Map.of());
            }
        };
        var request = new RuntimeAgentExecutionRequest("GOOGLE_ADK", "query", null,
            null, null, null, Map.of());
        assertThat(new DefaultAgentRuntimeDispatcher(List.of(adapter)).execute(request).output()).isEqualTo("ok");
    }

    @Test
    void dispatchesHealthOnlyToTheExactEngineAdapter() {
        AgentRuntimeAdapter adapter = new AgentRuntimeAdapter() {
            @Override public String adapterId() { return "openai"; }
            @Override public boolean supports(String engine) { return "OPENAI_COMPATIBLE".equals(engine); }
            @Override public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
                return new RuntimeAgentExecutionResult("COMPLETED", "", Map.of());
            }
            @Override public AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
                return new AgentRuntimeHealthResult("READY", Map.of("modelName", request.attributes().get("modelName")));
            }
        };

        var dispatcher = new DefaultAgentRuntimeDispatcher(List.of(adapter));

        assertThat(dispatcher.health("OPENAI_COMPATIBLE", Map.of("modelName", "published-model")))
            .satisfies(result -> {
                assertThat(result.status()).isEqualTo("READY");
                assertThat(result.details()).containsEntry("modelName", "published-model");
            });
        assertThat(dispatcher.health("GOOGLE_ADK", Map.of()).status()).isEqualTo("ENGINE_NOT_REGISTERED");
    }
}
