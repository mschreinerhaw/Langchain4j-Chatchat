package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterpretationPlanCheckpointIdentityTest {

    @Test
    void persistsEveryCompleteCheckpointIdentityComponentAndReusesWhenStable() {
        Scenario scenario = scenario(null);

        assertThat(scenario.runtime().execute(scenario.request("1.0", baseAttributes())).success()).isTrue();
        PlanStepCheckpoint checkpoint = scenario.store().planStepCheckpoints(RUN_ID).stream()
            .filter(value -> value.stepId() == 1)
            .findFirst()
            .orElseThrow();

        assertThat(checkpoint.schemaVersion()).isEqualTo("plan_step_checkpoint_v3");
        assertThat(checkpoint.committed()).isTrue();
        assertThat(checkpoint.checkpointFingerprint()).isNotBlank();
        assertThat(checkpoint.identityFingerprints()).containsOnlyKeys(
            "planVersion", "nodeDefinition", "actualInput", "dependencyResults",
            "toolContract", "modelConfig", "governanceContract", "executionEnvironment");

        InterpretationPlanRuntime.ExecutionResult restored = scenario.runtime().execute(
            scenario.request("1.0", baseAttributes()));
        assertThat(restored.success()).isTrue();
        assertThat(restored.steps()).isEmpty();
        assertThat(restored.metadata()).containsEntry("reusedPlanStepIds", List.of(1, 2));
        assertThat(scenario.toolCalls()).hasValue(1);
    }

    @Test
    void invalidatesWhenPlanVersionDrifts() {
        assertDrift("1.0", "2.0", baseAttributes(), baseAttributes(), null, null);
    }

    @Test
    void invalidatesWhenActualResolvedInputDrifts() {
        InterpretationPlanRuntime.StepInputEnricher enricher = request -> {
            Map<String, Object> input = new LinkedHashMap<>(request.input());
            input.put("runtimeValue", request.executionRequest().attributes().get("runtimeInputRevision"));
            return input;
        };
        Map<String, Object> first = attributesWith("runtimeInputRevision", "input-v1");
        Map<String, Object> second = attributesWith("runtimeInputRevision", "input-v2");
        assertDrift("1.0", "1.0", first, second, null, enricher);
    }

    @Test
    void invalidatesWhenToolContractDrifts() {
        assertDrift("1.0", "1.0", baseAttributes(), baseAttributes(),
            ToolMetadata.builder().id(TOOL).version("2.0.0").riskLevel("low").build(), null);
    }

    @Test
    void invalidatesWhenModelConfigurationDrifts() {
        assertDrift("1.0", "1.0",
            attributesWith("checkpointModelConfig", Map.of("model", "qwen", "temperature", 0.1)),
            attributesWith("checkpointModelConfig", Map.of("model", "qwen", "temperature", 0.2)),
            null, null);
    }

    @Test
    void invalidatesWhenGovernanceContractDrifts() {
        assertDrift("1.0", "1.0",
            attributesWith(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE,
                Map.of("contractVersion", "v1", "checksumSha256", "checksum-1", "rules", Map.of("immutable", true))),
            attributesWith(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE,
                Map.of("contractVersion", "v2", "checksumSha256", "checksum-2", "rules", Map.of("immutable", true))),
            null, null);
    }

    @Test
    void invalidatesWhenExecutionEnvironmentDrifts() {
        assertDrift("1.0", "1.0",
            attributesWith("checkpointExecutionEnvironment", Map.of("env", "DEV", "runtime", "2026.08.1")),
            attributesWith("checkpointExecutionEnvironment", Map.of("env", "DEV", "runtime", "2026.08.2")),
            null, null);
    }

    @Test
    void upstreamDriftInvalidatesDependentResultIdentity() {
        InterpretationPlanRuntime.StepInputEnricher enricher = request -> {
            Map<String, Object> input = new LinkedHashMap<>(request.input());
            if (request.step().id() == 1) {
                input.put("runtimeValue", request.executionRequest().attributes().get("runtimeInputRevision"));
            }
            return input;
        };
        Scenario scenario = scenario(enricher);
        assertThat(scenario.runtime().execute(scenario.request(
            "1.0", attributesWith("runtimeInputRevision", "dependency-v1"))).success()).isTrue();

        InterpretationPlanRuntime.ExecutionResult changed = scenario.runtime().execute(scenario.request(
            "1.0", attributesWith("runtimeInputRevision", "dependency-v2")));

        assertThat(changed.success()).isTrue();
        assertThat(changed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(changed.metadata()).containsEntry("reusedPlanStepIds", List.of());
        assertThat(scenario.toolCalls()).hasValue(2);
    }

    private void assertDrift(String firstPlanVersion,
                             String secondPlanVersion,
                             Map<String, Object> firstAttributes,
                             Map<String, Object> secondAttributes,
                             ToolMetadata changedMetadata,
                             InterpretationPlanRuntime.StepInputEnricher enricher) {
        Scenario scenario = scenario(enricher);
        assertThat(scenario.runtime().execute(
            scenario.request(firstPlanVersion, firstAttributes)).success()).isTrue();
        if (changedMetadata != null) {
            scenario.toolMetadata().set(changedMetadata);
        }

        InterpretationPlanRuntime.ExecutionResult changed = scenario.runtime().execute(
            scenario.request(secondPlanVersion, secondAttributes));

        assertThat(changed.success()).isTrue();
        assertThat(changed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(changed.metadata()).containsEntry("reusedPlanStepIds", List.of());
        assertThat(scenario.toolCalls()).hasValue(2);
    }

    private Scenario scenario(InterpretationPlanRuntime.StepInputEnricher enricher) {
        ToolRegistry registry = mock(ToolRegistry.class);
        AtomicReference<ToolMetadata> metadata = new AtomicReference<>(
            ToolMetadata.builder().id(TOOL).version("1.0.0").riskLevel("low").build());
        when(registry.hasTool(TOOL)).thenReturn(true);
        when(registry.getToolMetadata(TOOL)).thenAnswer(ignored -> metadata.get());
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        AtomicInteger calls = new AtomicInteger();
        when(tools.execute(any())).thenAnswer(ignored -> new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("evidence-" + calls.incrementAndGet()))),
            metadata.get(), null, "success", Map.of()));
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), store, null,
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "complete"), enricher);
        return new Scenario(runtime, registry, store, calls, metadata);
    }

    private Map<String, Object> baseAttributes() {
        return Map.of(
            "__agentRunId", RUN_ID,
            "workflowExecutionAttempt", 0,
            "checkpointModelConfig", Map.of("model", "qwen", "temperature", 0.1),
            "checkpointExecutionEnvironment", Map.of("env", "DEV", "runtime", "2026.08.1")
        );
    }

    private Map<String, Object> attributesWith(String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>(baseAttributes());
        attributes.put(key, value);
        return Map.copyOf(attributes);
    }

    private static InterpretationPlan plan(String version) {
        InterpretationPlan.Step search = new InterpretationPlan.Step(
            1, "mcp_tool", TOOL, Map.of("query", "stable"), List.of(), null, null);
        InterpretationPlan.Step answer = new InterpretationPlan.Step(
            2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null);
        return new InterpretationPlan(
            version,
            new InterpretationPlan.Intent("generic", "answer", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(search, answer)),
            new InterpretationPlan.ExecutionPolicy(4, false, List.of(TOOL), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );
    }

    private record Scenario(InterpretationPlanRuntime runtime,
                            ToolRegistry registry,
                            InMemoryAgentRunStore store,
                            AtomicInteger toolCalls,
                            AtomicReference<ToolMetadata> toolMetadata) {
        InterpretationPlanRuntime.ExecutionRequest request(String version, Map<String, Object> attributes) {
            return new InterpretationPlanRuntime.ExecutionRequest(
                plan(version), registry, List.of(TOOL),
                "tenant", "request", "conversation", "user", attributes);
        }
    }

    private static final String RUN_ID = "complete-checkpoint-identity-run";
    private static final String TOOL = "evidence_search";
}
