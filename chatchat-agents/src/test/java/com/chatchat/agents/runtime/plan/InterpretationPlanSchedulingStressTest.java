package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterpretationPlanSchedulingStressTest {

    private static final int CONCURRENCY = 32;
    private static final List<String> NORMAL_TOOLS = List.of(
        "stress_source_a", "stress_source_b", "stress_source_c", "stress_source_d");
    private static final List<String> BRANCH_TOOLS = List.of("stress_branch_a", "stress_branch_b");

    @Test
    void ordinaryReadyNodesStayJavaScheduledUnderConcurrentLoad() {
        int requests = 240;
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ToolRegistry registry = registry(NORMAL_TOOLS);
        ToolRuntimeService tools = successfulTools(toolCalls);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), request -> {
                modelCalls.incrementAndGet();
                return InterpretationPlanRuntime.DagDecision.abort("ordinary DAG must not call model scheduler");
            });
        InterpretationPlan plan = ordinaryParallelPlan();

        StressRun run = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> runConcurrent(
            requests,
            index -> runtime.execute(request(plan, registry, NORMAL_TOOLS, "ordinary", index))
        ));

        assertThat(run.results()).allSatisfy(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsEntry("llmDagDecisionCount", 0);
        });
        assertThat(toolCalls).hasValue(requests * NORMAL_TOOLS.size());
        assertThat(modelCalls).hasValue(0);
        printResult("ordinary_java_ready", run, toolCalls.get(), modelCalls.get());
    }

    @Test
    void semanticBranchesUseOneBoundedModelDecisionPerRequestUnderLoad() {
        int requests = 160;
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ToolRegistry registry = registry(BRANCH_TOOLS);
        ToolRuntimeService tools = successfulTools(toolCalls);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), request -> {
                modelCalls.incrementAndGet();
                assertThat(request.decisionPurpose()).isEqualTo("SEMANTIC_BRANCH_ARBITRATION");
                assertThat(request.readyStepIds()).containsExactly(1, 2);
                return InterpretationPlanRuntime.DagDecision.executeStep(2, "choose branch B");
            });
        InterpretationPlan plan = semanticBranchPlan();

        StressRun run = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> runConcurrent(
            requests,
            index -> runtime.execute(request(plan, registry, BRANCH_TOOLS, "branch", index))
        ));

        assertThat(run.results()).allSatisfy(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.metadata())
                .containsEntry("llmDagDecisionCount", 1)
                .containsEntry("semanticBranchSkippedStepIds", List.of(1));
        });
        assertThat(toolCalls).hasValue(requests);
        assertThat(modelCalls).hasValue(requests);
        printResult("semantic_branch", run, toolCalls.get(), modelCalls.get());
    }

    @Test
    void illegalModelSelectionsAreRejectedWithoutToolAmplificationUnderLoad() {
        int requests = 160;
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ToolRegistry registry = registry(BRANCH_TOOLS);
        ToolRuntimeService tools = successfulTools(toolCalls);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), request -> {
                modelCalls.incrementAndGet();
                return InterpretationPlanRuntime.DagDecision.finalAnswer(
                    3, "illegal", "attempt to bypass Runtime Ready set");
            });
        InterpretationPlan plan = semanticBranchPlan();

        StressRun run = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> runConcurrent(
            requests,
            index -> runtime.execute(request(plan, registry, BRANCH_TOOLS, "guard", index))
        ));

        assertThat(run.results()).allSatisfy(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("DAG_DECISION_REJECTED");
            assertThat(result.errorMessage()).contains("outside the Runtime Ready set");
        });
        assertThat(toolCalls).hasValue(0);
        assertThat(modelCalls).hasValue(requests);
        printResult("ready_guard_rejection", run, toolCalls.get(), modelCalls.get());
    }

    private StressRun runConcurrent(int requests,
                                    java.util.function.IntFunction<InterpretationPlanRuntime.ExecutionResult> task)
        throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        long started = System.nanoTime();
        try {
            List<Callable<InterpretationPlanRuntime.ExecutionResult>> jobs = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                int requestIndex = index;
                jobs.add(() -> task.apply(requestIndex));
            }
            List<Future<InterpretationPlanRuntime.ExecutionResult>> futures = executor.invokeAll(jobs);
            List<InterpretationPlanRuntime.ExecutionResult> results = new ArrayList<>(requests);
            for (Future<InterpretationPlanRuntime.ExecutionResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return new StressRun(List.copyOf(results), System.nanoTime() - started);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ToolRegistry registry(List<String> names) {
        ToolRegistry registry = mock(ToolRegistry.class);
        for (String name : names) {
            when(registry.hasTool(name)).thenReturn(true);
            when(registry.getToolMetadata(name)).thenReturn(metadata(name));
        }
        return registry;
    }

    private ToolRuntimeService successfulTools(AtomicInteger calls) {
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            calls.incrementAndGet();
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("tool", request.getToolName(), "status", "ok")),
                metadata(request.getToolName()), null, "success", Map.of());
        });
        return service;
    }

    private ToolMetadata metadata(String tool) {
        return ToolMetadata.builder().id(tool).version("1.0.0").riskLevel("low").build();
    }

    private InterpretationPlanRuntime.ExecutionRequest request(InterpretationPlan plan,
                                                               ToolRegistry registry,
                                                               List<String> allowedTools,
                                                               String scenario,
                                                               int index) {
        String id = scenario + "-" + index;
        return new InterpretationPlanRuntime.ExecutionRequest(
            plan, registry, allowedTools, "tenant-" + (index % 8), id, id, "stress-user", Map.of());
    }

    private InterpretationPlan ordinaryParallelPlan() {
        return plan(
            List.of(
                toolStep(1, NORMAL_TOOLS.get(0), List.of()),
                toolStep(2, NORMAL_TOOLS.get(1), List.of()),
                toolStep(3, NORMAL_TOOLS.get(2), List.of()),
                toolStep(4, NORMAL_TOOLS.get(3), List.of()),
                finalStep(5, List.of(1, 2, 3, 4))
            ),
            List.of(),
            NORMAL_TOOLS,
            true
        );
    }

    private InterpretationPlan semanticBranchPlan() {
        List<InterpretationPlan.Step> steps = List.of(
                toolStep(1, BRANCH_TOOLS.get(0), List.of()),
                toolStep(2, BRANCH_TOOLS.get(1), List.of()),
                finalStep(3, List.of())
            );
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("stress", "verify first-class branch scheduler", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(steps, List.of(), List.of(),
            List.of(), null, null,
            List.of(
                new InterpretationPlan.ConditionalEdge(1, 3, "source-route", "source A authoritative", 1, false),
                new InterpretationPlan.ConditionalEdge(2, 3, "source-route", "source B authoritative", 2, true)
            ),
            List.of(new InterpretationPlan.BranchGroup("source-route", List.of(1, 2), 3, "exclusive", "llm"))),
            new InterpretationPlan.ExecutionPolicy(steps.size(), false, BRANCH_TOOLS, List.of(), 30_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of())
        );
    }

    private InterpretationPlan plan(List<InterpretationPlan.Step> steps,
                                    List<InterpretationPlan.DependencyContract> dependencies,
                                    List<String> allowedTools,
                                    boolean parallel) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("stress", "verify hybrid DAG scheduler", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(steps, List.of(), dependencies, List.of(), null),
            new InterpretationPlan.ExecutionPolicy(steps.size(), parallel, allowedTools, List.of(), 30_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of())
        );
    }

    private InterpretationPlan.Step toolStep(int id, String tool, List<Integer> dependencies) {
        return new InterpretationPlan.Step(id, "mcp_tool", tool, Map.of(), dependencies, null, null);
    }

    private InterpretationPlan.Step finalStep(int id, List<Integer> dependencies) {
        return new InterpretationPlan.Step(
            id, "final_answer", "", Map.of("answer", "stress completed"), dependencies, null, null);
    }

    private void printResult(String scenario, StressRun run, int toolCalls, int modelCalls) {
        double seconds = run.elapsedNanos() / 1_000_000_000.0;
        double throughput = run.results().size() / Math.max(seconds, 0.001);
        System.out.printf(
            "STRESS_RESULT scenario=%s requests=%d concurrency=%d elapsedMs=%.0f throughputRps=%.2f toolCalls=%d modelCalls=%d%n",
            scenario, run.results().size(), CONCURRENCY, run.elapsedNanos() / 1_000_000.0,
            throughput, toolCalls, modelCalls);
    }

    private record StressRun(List<InterpretationPlanRuntime.ExecutionResult> results, long elapsedNanos) {
    }
}
