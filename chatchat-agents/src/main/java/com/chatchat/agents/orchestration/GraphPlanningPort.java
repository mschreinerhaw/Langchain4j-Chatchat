package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.analysis.graph.AnalysisExecutionGraph;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Graph-owned planning stages; the existing planner owns model/protocol repair behavior. */
final class GraphPlanningPort implements AgentPlanningPort {
    private final AgentPlanningPort delegate;
    GraphPlanningPort(AgentPlanningPort delegate) { this.delegate = Objects.requireNonNull(delegate); }

    @Override public PlannerExecutionResult plan(AgentPlanningRequest request) {
        var output = new AtomicReference<PlannerExecutionResult>();
        var execution = new AnalysisExecutionGraph().execute(List.of(
            new AnalysisExecutionGraph.Step("binding_preflight", () -> {
                Objects.requireNonNull(request, "Planning request is required");
                Objects.requireNonNull(request.chatModel(), "Planning model is required");
                if (request.query() == null || request.query().isBlank())
                    throw new IllegalArgumentException("Analysis question is required before planning");
                // Binding identifiers remain authoritative inputs; do not infer files or tools from prose.
                if (request.boundDocumentIds().stream().anyMatch(String::isBlank))
                    throw new IllegalArgumentException("Bound document identifiers must not be blank");
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("driver_planner", () -> {
                output.set(Objects.requireNonNull(delegate.plan(request), "Planner returned no result"));
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("plan_product", () -> {
                Objects.requireNonNull(output.get().decision(), "Planner returned no decision");
                return AnalysisExecutionGraph.Status.COMPLETED;
            })), () -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Planning cancelled");
            });
        var result = output.get();
        return new PlannerExecutionResult(result.plan(), result.candidateAnswer(), result.taskContract(),
            result.decision(), execution.nodes());
    }
}
