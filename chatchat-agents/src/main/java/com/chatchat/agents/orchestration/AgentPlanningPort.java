package com.chatchat.agents.orchestration;

/** Engine-facing planning boundary. Prompt construction and protocol parsing stay behind this port. */
interface AgentPlanningPort {

    PlannerExecutionResult plan(AgentPlanningRequest request);
}
