package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import java.util.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** One invocation, one optional repair wave, no edge back to model review. */
final class ClaimAcceptanceGraph {
    interface Work<T> {
        void build();
        void validate();
        void review();
        boolean needsRepair();
        void repair();
        default boolean hasPatches() { return true; }
        void revalidate();
        T assemble();
    }
    record Result<T>(T value, List<Map<String, Object>> nodes) {}

    <T> Result<T> execute(Work<T> work) {
        List<Map<String, Object>> trace = new ArrayList<>();
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        try {
            var graph = new StateGraph<AgentState>(AgentState::new);
            Map<String, Runnable> actions = new LinkedHashMap<>();
            actions.put("BUILD_CLAIMS", work::build);
            actions.put("PROGRAMMATIC_VALIDATE", work::validate);
            actions.put("SEMANTIC_REVIEW", work::review);
            actions.put("REPAIR_CLAIMS", work::repair);
            actions.put("VALIDATE_ONCE", work::revalidate);
            actions.put("ASSEMBLE_VERIFIED_REPORT", () -> result.set(work.assemble()));
            for (var action : actions.entrySet()) {
                graph.addNode(action.getKey(), node_async(state -> {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Acceptance cancelled");
                    long start = System.nanoTime();
                    action.getValue().run();
                    trace.add(Map.of("node", action.getKey(), "durationMs", (System.nanoTime() - start) / 1_000_000));
                    return Map.of("phase", action.getKey());
                }));
            }
            graph.addEdge(StateGraph.START, "BUILD_CLAIMS");
            graph.addEdge("BUILD_CLAIMS", "PROGRAMMATIC_VALIDATE");
            graph.addEdge("PROGRAMMATIC_VALIDATE", "SEMANTIC_REVIEW");
            graph.addConditionalEdges("SEMANTIC_REVIEW", edge_async(state -> work.needsRepair() ? "repair" : "assemble"),
                Map.of("repair", "REPAIR_CLAIMS", "assemble", "ASSEMBLE_VERIFIED_REPORT"));
            graph.addConditionalEdges("REPAIR_CLAIMS", edge_async(state -> work.hasPatches() ? "validate" : "assemble"),
                Map.of("validate", "VALIDATE_ONCE", "assemble", "ASSEMBLE_VERIFIED_REPORT"));
            graph.addEdge("VALIDATE_ONCE", "ASSEMBLE_VERIFIED_REPORT");
            graph.addEdge("ASSEMBLE_VERIFIED_REPORT", StateGraph.END);
            var compiled = graph.compile();
            compiled.setMaxIterations(32);
            compiled.invoke(Map.of());
            return new Result<>(Objects.requireNonNull(result.get()), List.copyOf(trace));
        } catch (org.bsc.langgraph4j.GraphStateException ex) {
            throw new IllegalStateException("Invalid claim acceptance graph", ex);
        } catch (RuntimeException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
            }
            throw ex;
        }
    }
}
