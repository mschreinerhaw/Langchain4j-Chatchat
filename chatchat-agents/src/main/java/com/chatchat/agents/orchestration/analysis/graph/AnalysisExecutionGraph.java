package com.chatchat.agents.orchestration.analysis.graph;

import org.bsc.langgraph4j.StateGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** Invocation-local graph. No model, checkpoint store, executor or retry policy is created here. */
public final class AnalysisExecutionGraph {
    public enum Status {
        READY, NEEDS_CLARIFICATION, NEEDS_MORE_EVIDENCE, NO_EVIDENCE, BLOCKED, FAILED, CANCELLED,
        COMPLETED, COMPLETED_WITH_LIMITATIONS
    }
    public record Step(String name, Supplier<Status> action) {}
    public record Result(Status status, List<Map<String, Object>> nodes) {}

    public Result execute(List<Step> steps, Runnable cancellationGuard) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Analysis graph requires nodes");
        Runnable guard = cancellationGuard == null ? () -> {} : cancellationGuard;
        List<Map<String, Object>> metrics = new ArrayList<>();
        RuntimeException[] failure = new RuntimeException[1];
        try {
            var graph = new StateGraph<AnalysisState>(AnalysisState::new);
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                String next = i + 1 < steps.size() ? steps.get(i + 1).name() : StateGraph.END;
                graph.addNode(step.name(), node_async(state -> {
                    long started = System.nanoTime();
                    Status status = Status.FAILED;
                    try {
                        guard.run();
                        status = Objects.requireNonNull(step.action().get(), "Node must return an explicit status");
                    } catch (CancellationException ex) {
                        status = Status.CANCELLED;
                        failure[0] = ex;
                    } catch (RuntimeException ex) {
                        failure[0] = ex;
                    } finally {
                        metrics.add(Map.of("node", step.name(), "status", status.name(),
                            "executionTimeMs", Math.max(0, (System.nanoTime() - started) / 1_000_000)));
                    }
                    return Map.of("status", status.name(), "phase", step.name());
                }));
                Map<String, String> routes = new LinkedHashMap<>();
                for (Status status : Status.values()) {
                    routes.put(status.name(), status == Status.READY ? next : StateGraph.END);
                }
                graph.addConditionalEdges(step.name(), edge_async(state ->
                    state.status().name()), routes);
            }
            graph.addEdge(StateGraph.START, steps.get(0).name());
            var compiled = graph.compile();
            // The library counts routing and terminal transitions as well as work nodes.
            compiled.setMaxIterations(steps.size() * 4 + 8);
            var state = compiled.invoke(Map.of("status", Status.READY.name())).orElseThrow();
            if (failure[0] != null) throw failure[0];
            Status status = state.status();
            if (status == Status.READY) throw new IllegalStateException("Graph ended without terminal disposition");
            return new Result(status, List.copyOf(metrics));
        } catch (org.bsc.langgraph4j.GraphStateException ex) {
            throw new IllegalStateException("Invalid analysis graph", ex);
        }
    }
}
