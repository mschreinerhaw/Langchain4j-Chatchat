package com.chatchat.runtime.skill.core;

import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class DefaultAgentRuntimeDispatcherTest {
    @Test
    void failsClosedWhenAnEngineAdapterIsNotRegistered() {
        var request = new AgentRuntimeAdapter.ExecutionRequest("GOOGLE_ADK", "query", null,
            null, null, null, Map.of());
        var result = new DefaultAgentRuntimeDispatcher(List.of()).execute(request);
        assertThat(result.status()).isEqualTo("ENGINE_NOT_REGISTERED");
    }

    @Test
    void dispatchesOnlyToTheExactEngineAdapter() {
        AgentRuntimeAdapter adapter = new AgentRuntimeAdapter() {
            @Override public String adapterId() { return "google-adk"; }
            @Override public boolean supports(String engine) { return "GOOGLE_ADK".equals(engine); }
            @Override public ExecutionResult execute(ExecutionRequest request) {
                return new ExecutionResult("COMPLETED", "ok", Map.of());
            }
        };
        var request = new AgentRuntimeAdapter.ExecutionRequest("GOOGLE_ADK", "query", null,
            null, null, null, Map.of());
        assertThat(new DefaultAgentRuntimeDispatcher(List.of(adapter)).execute(request).output()).isEqualTo("ok");
    }
}
