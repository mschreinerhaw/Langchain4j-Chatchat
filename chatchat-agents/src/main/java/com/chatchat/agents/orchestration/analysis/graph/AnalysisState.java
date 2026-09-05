package com.chatchat.agents.orchestration.analysis.graph;

import org.bsc.langgraph4j.state.AgentState;
import java.util.Map;

/** LangGraph state contains serializable control fields; evidence remains in Runtime stores. */
public final class AnalysisState extends AgentState {
    public AnalysisState(Map<String, Object> values) { super(values); }
    public AnalysisExecutionGraph.Status status() {
        return AnalysisExecutionGraph.Status.valueOf(this.<String>value("status").orElseThrow());
    }
    public String phase() { return this.<String>value("phase").orElse("START"); }
}
