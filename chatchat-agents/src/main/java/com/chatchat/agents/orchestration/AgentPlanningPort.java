package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.planning.model.AgentDecision;
import java.util.List;
import java.util.Map;
import com.chatchat.agents.assessment.RuntimeAnswerCandidate;
import com.chatchat.agents.assessment.TaskContract;

/** Engine-facing planning boundary. Prompt construction and protocol parsing stay behind this port. */
interface AgentPlanningPort {

    PlannerExecutionResult plan(AgentPlanningRequest request);
}

record PlannerExecutionResult(
    PlannerPlanProduct plan,
    RuntimeAnswerCandidate candidateAnswer,
    TaskContract taskContract,
    AgentDecision decision,
    List<Map<String, Object>> graphNodes
) {
    PlannerExecutionResult(PlannerPlanProduct plan, RuntimeAnswerCandidate candidateAnswer,
                           TaskContract taskContract, AgentDecision decision) {
        this(plan, candidateAnswer, taskContract, decision, List.of());
    }
    PlannerExecutionResult {
        graphNodes = graphNodes == null ? List.of() : List.copyOf(graphNodes);
    }
}
