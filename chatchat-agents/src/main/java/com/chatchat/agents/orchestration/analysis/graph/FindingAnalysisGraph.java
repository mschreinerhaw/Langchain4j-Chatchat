package com.chatchat.agents.orchestration.analysis.graph;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.function.Predicate;
import java.util.concurrent.CancellationException;
import org.bsc.langgraph4j.StateGraph;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** Invocation-local products; durable evidence and recovery remain owned by Runtime. */
public final class FindingAnalysisGraph {
    public record Result<T>(T product, List<String> visitedNodes) {}

    public <T> Result<T> execute(Supplier<T> analyze, Predicate<T> needsRepair,
                                 UnaryOperator<T> repair) {
        return execute(analyze, UnaryOperator.identity(), needsRepair, repair);
    }

    public <T> Result<T> execute(Supplier<T> analyze, UnaryOperator<T> validate,
                                 Predicate<T> needsRepair, UnaryOperator<T> repair) {
        List<String> visited = new ArrayList<>();
        RuntimeException[] failure = new RuntimeException[1];
        try {
            var graph = new StateGraph<AnalysisState>(AnalysisState::new);
            graph.addNode("analyze", node_async(state -> {
                visited.add("analyze");
                try {
                    checkCancelled();
                    return Map.of("product", analyze.get(), "failed", false);
                } catch (RuntimeException ex) {
                    failure[0] = ex;
                    return Map.of("failed", true);
                }
            }));
            graph.addNode("validate", node_async(state -> {
                visited.add("validate");
                try {
                    checkCancelled();
                    T validated = validate.apply(state.<T>value("product").orElseThrow());
                    return Map.of("product", validated, "needsRepair", needsRepair.test(validated));
                } catch (RuntimeException ex) {
                    failure[0] = ex;
                    return Map.of("failed", true);
                }
            }));
            graph.addNode("repair", node_async(state -> {
                visited.add("repair");
                try {
                    checkCancelled();
                    return Map.of("product", repair.apply(state.<T>value("product").orElseThrow()), "repaired", true);
                } catch (CancellationException ex) {
                    failure[0] = ex;
                    return Map.of("failed", true);
                } catch (RuntimeException unavailable) {
                    // Keep the original validated product. A repair is never an unbounded retry.
                    return Map.of("repaired", true, "repairUnavailable", true);
                }
            }));
            graph.addEdge(StateGraph.START, "analyze");
            graph.addConditionalEdges("analyze", edge_async(state ->
                state.<Boolean>value("failed").orElse(false) ? "end" : "validate"),
                Map.of("end", StateGraph.END, "validate", "validate"));
            graph.addConditionalEdges("validate", edge_async(state -> {
                if (state.<Boolean>value("failed").orElse(false)) return "end";
                return state.<Boolean>value("needsRepair").orElse(false)
                    && !state.<Boolean>value("repaired").orElse(false) ? "repair" : "end";
            }), Map.of("repair", "repair", "end", StateGraph.END));
            graph.addConditionalEdges("repair", edge_async(state ->
                state.<Boolean>value("failed").orElse(false) ? "end" : "validate"),
                Map.of("end", StateGraph.END, "validate", "validate"));
            var compiled = graph.compile();
            compiled.setMaxIterations(24);
            var finalState = compiled.invoke(Map.of("repaired", false)).orElseThrow();
            if (failure[0] != null) throw failure[0];
            return new Result<>(finalState.<T>value("product").orElseThrow(), List.copyOf(visited));
        } catch (org.bsc.langgraph4j.GraphStateException invalid) {
            throw new IllegalStateException("Invalid finding analysis graph", invalid);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Analysis cancelled");
    }
}
