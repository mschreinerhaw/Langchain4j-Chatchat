package com.chatchat.agents.runtime.plan.transformation;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterpretationPlanTransformationPipelineTest {

    @Test
    void executesSupportedPassesInDeclaredOrder() {
        List<String> calls = new ArrayList<>();
        InterpretationPlanTransformationPipeline pipeline = new InterpretationPlanTransformationPipeline(List.of(
            pass("normalize", PlanPassKind.NORMALIZATION, (workspace, context) -> calls.add("normalize")),
            pass("repair", PlanPassKind.REQUIRED_REPAIR, (workspace, context) -> calls.add("repair")),
            new FunctionalInterpretationPlanPass(
                "unsupported", PlanPassKind.OPTIONAL_OPTIMIZATION,
                (workspace, context) -> false,
                (workspace, context) -> calls.add("unsupported")),
            pass("policy", PlanPassKind.POLICY_GUARD, (workspace, context) -> calls.add("policy"))
        ));

        pipeline.optimize(new PlanTransformationWorkspace(plan()), new PlanTransformationContext(null, null));

        assertThat(calls).containsExactly("normalize", "repair", "policy");
    }

    @Test
    void rollsBackOnlyTheFailedOptionalOptimizationPass() {
        PlanTransformationWorkspace workspace = new PlanTransformationWorkspace(plan());
        InterpretationPlan.Step original = workspace.steps().get(0);
        InterpretationPlanTransformationPipeline pipeline = new InterpretationPlanTransformationPipeline(List.of(
            pass("optional", PlanPassKind.OPTIONAL_OPTIMIZATION, (state, context) -> {
                state.steps(List.of(new InterpretationPlan.Step(
                    99, "mcp_tool", "changed", Map.of(), List.of(), null, null)));
                state.markApplied("optional");
                throw new IllegalStateException("optional failure");
            }),
            pass("required", PlanPassKind.REQUIRED_REPAIR,
                (state, context) -> state.markApplied("required"))
        ));

        pipeline.optimize(workspace, new PlanTransformationContext(null, null));

        assertThat(workspace.steps()).containsExactly(original);
        assertThat(workspace.appliedPasses()).containsExactly("required");
        assertThat(workspace.passFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.passId()).isEqualTo("optional");
            assertThat(failure.passKind()).isEqualTo(PlanPassKind.OPTIONAL_OPTIMIZATION);
            assertThat(failure.rolledBack()).isTrue();
        });
    }

    @Test
    void stopsWhenRequiredRepairFails() {
        InterpretationPlanTransformationPipeline pipeline = new InterpretationPlanTransformationPipeline(List.of(
            pass("required-dag-repair", PlanPassKind.REQUIRED_REPAIR,
                (workspace, context) -> { throw new IllegalArgumentException("invalid DAG"); })
        ));

        assertThatThrownBy(() -> pipeline.optimize(
            new PlanTransformationWorkspace(plan()), new PlanTransformationContext(null, null)))
            .isInstanceOf(InterpretationPlanTransformationPipeline.PlanTransformationException.class)
            .satisfies(error -> {
                InterpretationPlanTransformationPipeline.PlanTransformationException failure =
                    (InterpretationPlanTransformationPipeline.PlanTransformationException) error;
                assertThat(failure.passId()).isEqualTo("required-dag-repair");
                assertThat(failure.passKind()).isEqualTo(PlanPassKind.REQUIRED_REPAIR);
            });
    }

    private FunctionalInterpretationPlanPass pass(
        String id,
        PlanPassKind kind,
        java.util.function.BiConsumer<PlanTransformationWorkspace, PlanTransformationContext> action
    ) {
        return new FunctionalInterpretationPlanPass(id, kind, null, action);
    }

    private InterpretationPlan plan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("test", "pipeline", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "source", Map.of(), List.of(), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of("source"), List.of(), 1000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of())
        );
    }
}
