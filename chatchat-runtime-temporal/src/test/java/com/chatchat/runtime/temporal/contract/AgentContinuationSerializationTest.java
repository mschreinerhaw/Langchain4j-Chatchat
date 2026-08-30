package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContinuationSerializationTest {

    @Test
    void suspendedAgentAndPlanStateRoundTripsThroughWorkerPayloadJson() throws Exception {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("analysis", "customer assets", "low"),
            new InterpretationPlan.Context(List.of("customer=c-1"), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "asset_query",
                    Map.of("customerId", "c-1"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "",
                    Map.of("answer", "done"), List.of(1), null, null))),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("asset_query"),
                List.of(), 60_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of()));
        InterpretationPlanRuntime.StepExecution completed =
            new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "asset_query", true,
                Map.of("totalAssets", 100), null, null, null, 8L,
                Map.of("committedEvidence", true));
        PlanExecutionContinuation execution = new PlanExecutionContinuation(
            null, "tenant-a::request-a::attempt:0", plan, List.of(2), List.of(completed),
            List.of(), List.of(), 1,
            Map.of("tenantId", "tenant-a", "allowedTools", List.of("asset_query")));
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("run-a").requestId("request-a").tenantId("tenant-a")
            .query("analyze customer assets").availableTools(List.of("asset_query"))
            .attributes(Map.of("workflowExecutionAttempt", 0)).build();
        AgentPlanPipelineContinuation continuation = new AgentPlanPipelineContinuation(
            null, "continuation-a", request, plan, plan, execution,
            1, 0, 2, List.of(), List.of(Map.of("source", "tool")),
            List.of(), List.of("asset evidence collected"),
            Map.of("workflowExecutionAttempt", 0), Map.of("planFingerprint", "fp-a"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        String json = mapper.writeValueAsString(continuation);
        AgentPlanPipelineContinuation restored = mapper.readValue(
            json, AgentPlanPipelineContinuation.class);

        assertThat(restored.continuationId()).isEqualTo("continuation-a");
        assertThat(restored.request().getRunId()).isEqualTo("run-a");
        assertThat(restored.execution().remainingStepIds()).containsExactly(2);
        assertThat(restored.execution().completedSteps()).hasSize(1);
        assertThat(restored.execution().completedSteps().get(0).metadata())
            .containsEntry("committedEvidence", true);
    }
}
