package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.analysis.loop.AnalysisRefinementCoordinator;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.chatchat.agents.orchestration.analysis.graph.InterpretationAnalysisGraph.Phase.FINALIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InterpretationRefinementAdmissionTest {
    @Test
    void failedExecutionReachesFinalizationWithoutCallingRewriteModel() throws Exception {
        AgentOrchestrationEngine host = mock(AgentOrchestrationEngine.class);
        set(host, "analysisRefinementCoordinator", new AnalysisRefinementCoordinator(mock(AgentToolNameResolver.class), 3));
        ChatModel model = mock(ChatModel.class);
        InterpretationPlanRewriter rewriter = mock(InterpretationPlanRewriter.class);
        Map<String, Object> metadata = new LinkedHashMap<>();
        var session = new InterpretationAnalysisSession(host, null, model, "analyze", "", "tenant",
            "request", "conversation", "user", List.of("query"), Map.of(), new ArrayList<>(),
            new ArrayList<>(), metadata, List.of(), List.of(), 5, 10, () -> false);
        var failure = new InterpretationPlanRuntime.ExecutionResult("STEP_FAILED", false, false,
            "Data conversion failed", null, List.of(), Map.of(), 1L);
        set(session, "currentResult", failure);
        set(session, "planAttemptResults", List.of(failure));
        set(session, "evidenceHistory", List.of(Map.of("missingEvidence", List.of("history"))));
        set(session, "maxRewriteTimes", 3);
        set(session, "rewriter", rewriter);

        assertThat(session.refinementGate()).isEqualTo(FINALIZE);
        assertThat(metadata).containsEntry("refinementStopReason", "no_verified_new_retrieval_path");
        verifyNoInteractions(model, rewriter);
    }

    private void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
