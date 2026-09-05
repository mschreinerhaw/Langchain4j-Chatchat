package com.chatchat.agents.orchestration.analysis.graph;

import org.bsc.langgraph4j.StateGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** Owns analysis phase edges and the evidence refinement cycle, without owning side effects. */
public final class InterpretationAnalysisGraph {
    public enum Phase {
        PREPARE, INITIAL_DATA, INITIAL_ANALYSIS, PREPARE_REFINEMENT, REFINEMENT_GATE,
        REFINEMENT_PLAN, REFINEMENT_DATA, REFINEMENT_ANALYSIS, FINAL_INITIAL, FINAL_REFINED, FINALIZE, END
    }
    private static final Map<Phase, List<Phase>> EDGES = Map.ofEntries(
        Map.entry(Phase.PREPARE, List.of(Phase.INITIAL_DATA)),
        Map.entry(Phase.INITIAL_DATA, List.of(Phase.INITIAL_ANALYSIS, Phase.END)),
        Map.entry(Phase.INITIAL_ANALYSIS, List.of(Phase.FINAL_INITIAL, Phase.PREPARE_REFINEMENT)),
        Map.entry(Phase.PREPARE_REFINEMENT, List.of(Phase.REFINEMENT_GATE)),
        Map.entry(Phase.REFINEMENT_GATE, List.of(Phase.REFINEMENT_PLAN, Phase.FINALIZE)),
        Map.entry(Phase.REFINEMENT_PLAN, List.of(Phase.REFINEMENT_DATA, Phase.REFINEMENT_GATE, Phase.FINALIZE)),
        Map.entry(Phase.REFINEMENT_DATA, List.of(Phase.REFINEMENT_ANALYSIS, Phase.END)),
        Map.entry(Phase.REFINEMENT_ANALYSIS, List.of(Phase.FINAL_REFINED, Phase.FINALIZE, Phase.REFINEMENT_GATE)),
        Map.entry(Phase.FINAL_INITIAL, List.of(Phase.END)),
        Map.entry(Phase.FINAL_REFINED, List.of(Phase.END)),
        Map.entry(Phase.FINALIZE, List.of(Phase.END)));

    public void execute(Function<Phase, Phase> nodes, Map<String, Object> metadata) {
        RuntimeException[] failure = new RuntimeException[1];
        List<Map<String,Object>> history = new ArrayList<>();
        try {
            var graph = new StateGraph<AnalysisState>(AnalysisState::new);
            for (Phase phase : Phase.values()) {
                if (phase == Phase.END) continue;
                graph.addNode(phase.name(), node_async(state -> {
                    long started = System.nanoTime();
                    Phase next = Phase.END;
                    String status = "SUCCEEDED";
                    try {
                        next = java.util.Objects.requireNonNull(nodes.apply(phase));
                        if (!EDGES.get(phase).contains(next)) throw new IllegalStateException("Invalid analysis transition: " + phase + " -> " + next);
                    } catch (RuntimeException ex) {
                        failure[0] = ex;
                        next = Phase.END;
                        status = ex instanceof com.chatchat.agents.runtime.plan.execution.AgentPlanSuspendedException
                            ? "SUSPENDED" : ex instanceof java.util.concurrent.CancellationException ? "CANCELLED" : "FAILED";
                    }
                    history.add(Map.of("phase", phase.name(), "next", next.name(), "status", status,
                        "durationMs", Math.max(0, (System.nanoTime() - started) / 1_000_000)));
                    metadata.put("analysisPipelinePhase", phase.name());
                    metadata.put("analysisPipelineStatus", status);
                    metadata.put("analysisPipelineNodes", List.copyOf(history));
                    return Map.of("next", next.name(), "phase", phase.name());
                }));
                Map<String,String> routes = new LinkedHashMap<>();
                for (Phase next : EDGES.get(phase)) routes.put(next.name(), next == Phase.END ? StateGraph.END : next.name());
                routes.put(Phase.END.name(), StateGraph.END); // Failure/suspension exit only.
                graph.addConditionalEdges(phase.name(), edge_async(state -> state.<String>value("next").orElseThrow()), routes);
            }
            graph.addEdge(StateGraph.START, Phase.PREPARE.name());
            var compiled = graph.compile();
            compiled.setMaxIterations(512); // Secondary corruption guard; Runtime owns the smaller business budget.
            var state = compiled.invoke(Map.of()).orElseThrow();
            if (failure[0] != null) throw failure[0];
            if (!Phase.END.name().equals(state.<String>value("next").orElse(null)))
                throw new IllegalStateException("Analysis graph ended without a terminal transition");
        } catch (org.bsc.langgraph4j.GraphStateException ex) {
            metadata.put("analysisPipelineStatus", "FAILED");
            throw new IllegalStateException("Invalid interpretation analysis graph", ex);
        } catch (RuntimeException ex) {
            if (failure[0] == null) metadata.put("analysisPipelineStatus", "FAILED");
            throw ex;
        }
    }
}
