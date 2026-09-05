package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.planning.model.AgentDecision;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GraphPlanningPortTest {
    @Test void invalidBindingDoesNotCallTheModel() {
        var calls = new AtomicInteger();
        var planner = new GraphPlanningPort(request -> { calls.incrementAndGet(); return null; });
        assertThatThrownBy(() -> planner.plan(request("question", List.of(" "))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(request(" ", List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void graphReturnsExistingProductWithoutAnotherPlannerCall() {
        var calls = new AtomicInteger();
        var decision = new AgentDecision("final", null, Map.of(), "answer", "reason", Map.of(), true);
        var planner = new GraphPlanningPort(request -> {
            calls.incrementAndGet();
            return new PlannerExecutionResult(null, null, null, decision);
        });
        var result = planner.plan(request("question", List.of("document-1")));
        assertThat(result.decision()).isSameAs(decision);
        assertThat(result.graphNodes()).hasSize(3);
        assertThat(calls).hasValue(1);
    }

    @Test void transportFailureIsNotRetriedByGraph() {
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("provider unavailable");
        var planner = new GraphPlanningPort(request -> { calls.incrementAndGet(); throw failure; });
        assertThatThrownBy(() -> planner.plan(request("question", List.of()))).isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    private AgentPlanningRequest request(String query, List<String> documents) {
        return new AgentPlanningRequest(mock(ChatModel.class), query, "system", List.of(), List.of(),
            documents, List.of(), List.of(), false, false, null, null, Map.of());
    }
}
