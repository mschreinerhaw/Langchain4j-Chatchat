package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AnalysisPlanContractRegressionTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");

    private Map<String,Object> plan(String goal) {
        return Map.of("version", "1.0", "intent", Map.of("type", "analysis", "goal", goal),
            "context", Map.of("key_facts", List.of(), "assumptions", List.of(), "missing_info", List.of(), "constraints", List.of()),
            "plan", Map.of("steps", List.of(Map.of("id", 1, "action_type", "reasoning",
                "input", Map.of("objective", goal, "required_evidence", List.of("authorized evidence"),
                    "expected_output", "Supported answer", "acceptance_conditions", List.of("Cite evidence")), "depends_on", List.of()))));
    }

    @Test void missingPlanIsCorrectableWithoutBypassingTheGate() {
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        var metadata = new LinkedHashMap<String,Object>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            String instructions = ((SystemMessage) request.messages().get(0)).text();
            String state = ((UserMessage) request.messages().get(1)).singleText();
            assertThat(instructions).contains("next response MUST", "root-level \"plan\"")
                .doesNotContain("Optional response.plan");
            assertThat(state).contains("PLAN_REQUIRED", "requiredResponseField");
            var response = new LinkedHashMap<String,Object>(Map.of("schemaVersion", "model_native_analysis.v2",
                "decision", Map.of("action", "PARTIAL_COMPLETE"), "output_type", "final", "content", "Limited evidence answer"));
            if (turns.incrementAndGet() == 2) {
                assertThat(state).contains("INVALID_TURN_PROTOCOL");
                response.put("plan", plan("Assess available evidence"));
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(ModelProtocolJson.compact(response))).build();
        });
        var result = new ModelNativeAnalysisHarness(3).execute("Assess evidence", List.of(), model, scope,
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {});
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.markdown()).isEqualTo("Limited evidence answer");
        assertThat((List<?>) metadata.get("harnessPlanHistory")).hasSize(1);
    }

    @Test void revisionsRequireReasonAndRemainModelAuthoredWithoutDispatchingSteps() {
        var events = new ArrayList<Map<String,Object>>();
        var state = new HarnessPlanState(new LinkedHashMap<>(), AnalysisEvidenceSpillStore.disabled(), scope, events::add, "Question");
        assertThatThrownBy(() -> state.prepare(Map.of("evidenceRequests", List.of())))
            .hasMessageContaining("PLAN_REQUIRED");
        state.commit(state.prepare(Map.of("plan", plan("Explore initial evidence"))));
        assertThat(state.prepare(Map.of())).isNull();
        assertThatThrownBy(() -> state.prepare(Map.of("plan", plan("Explore newly discovered gap"))))
            .hasMessageContaining("PLAN_CHANGE_REASON_REQUIRED");
        state.commit(state.prepare(Map.of("plan", plan("Explore newly discovered gap"),
            "plan_change_reason", "Initial evidence lacks the requested field")));
        assertThat(state.view()).containsEntry("revision", 2).containsEntry("status", "ACCEPTED");
        assertThat(state.read(Map.of("revision", 1)).get("text").toString()).contains("Explore initial evidence");
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event).containsEntry("eventKind", "ANALYSIS_PLAN"));
    }
}
