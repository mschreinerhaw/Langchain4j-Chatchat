package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.workflow.*;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.execution.LocalWorkflowRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAnalysisWorkflowRuntimeTest {
    @Test
    void routesComputationIntentThroughParentLifecycleAndOperator() {
        AnalysisCapabilityOperator operator = new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(new ComputationEvidence(
                    "e-1", "max_drawdown", List.of("prices"), "-0.134", Map.of())), Map.of(), List.of());
            }
        };
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(operator)));
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(List.of(computation));
        KernelDataScope scope = KernelDataScope.system("request-1");
        AnalysisIntent intent = new AnalysisIntent("RISK_CALCULATION", List.of(),
            Set.of(AnalysisCapability.COMPUTATION), "UNSPECIFIED", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext("calculate max drawdown", scope,
            "risk-skill", List.of(), List.of(), List.of(), intent, Map.of()));

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPUTATION);
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).singleElement()
            .isInstanceOf(ComputationEvidence.class);
        assertThat(result.metadata()).containsEntry("executionMode", "INLINE");
    }

    @Test
    void compositeWorkflowMergesVerifiedEvidenceFromEachCapability() {
        AnalysisOperatorRegistry operators = new AnalysisOperatorRegistry(List.of(
            operator(AnalysisCapability.COMPUTATION, new ComputationEvidence(
                "calculation-1", "sum", List.of("data-1"), "42", Map.of())),
            operator(AnalysisCapability.STRUCTURED_DATA, new StructuredDataEvidence(
                "data-1", "orders", "select sum(amount) from orders", 1L,
                "2026-09-23", "42", Map.of()))));
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(operators);
        StructuredDataAnalysisWorkflow structured = new StructuredDataAnalysisWorkflow(operators);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("computation", computation);
        beans.addBean("structured", structured);
        CompositeAnalysisWorkflow composite = new CompositeAnalysisWorkflow(
            beans.getBeanProvider(AnalysisWorkflow.class));
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(
            List.of(computation, structured, composite));
        AnalysisIntent intent = new AnalysisIntent("ORDER_TOTAL", List.of(),
            Set.of(AnalysisCapability.STRUCTURED_DATA, AnalysisCapability.COMPUTATION),
            "CURRENT", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext("calculate current order total",
            KernelDataScope.system("request-2"), "finance-skill", List.of(), List.of(), List.of(),
            intent, Map.of()));

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPOSITE);
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence())
            .extracting(AnalysisEvidence::capability)
            .containsExactlyInAnyOrder(AnalysisCapability.STRUCTURED_DATA, AnalysisCapability.COMPUTATION);
    }

    @Test
    void explicitlyDurableAnalysisUsesWorkflowRuntimeWithoutChangingChildWorkflow() {
        AnalysisCapabilityOperator operator = operator(AnalysisCapability.COMPUTATION,
            new ComputationEvidence("e-2", "sum", List.of("input"), "7", Map.of()));
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(operator)));
        LocalWorkflowRuntime workflowRuntime = new LocalWorkflowRuntime(
            ForkJoinPool.commonPool(), new AgentRuntimeProperties());
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(
            List.of(computation), workflowRuntime);
        KernelDataScope scope = KernelDataScope.system("request-durable-1");
        AnalysisContext context = new AnalysisContext("calculate total", scope, "math-skill",
            List.of(), List.of(), List.of(),
            new AnalysisIntent("TOTAL", List.of(), Set.of(AnalysisCapability.COMPUTATION),
                "UNSPECIFIED", true), Map.of(AnalysisContext.EXECUTION_MODE_ATTRIBUTE, "DURABLE"));

        AnalysisExecutionOutcome result = runtime.analyze(context);

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPUTATION);
        assertThat(result.metadata()).containsEntry("executionMode", "DURABLE");
        assertThat(result.metadata().get("runtimeWorkflowId")).asString().startsWith("analysis-");
        assertThat(workflowRuntime.activeExecutionCount()).isZero();
    }

    private AnalysisCapabilityOperator operator(AnalysisCapability capability, AnalysisEvidence evidence) {
        return new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return capability; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(evidence), Map.of(), List.of());
            }
        };
    }
}
