package com.chatchat.common.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.routing.AnalysisWorkflowRouter;

import com.chatchat.common.kernel.KernelDataScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisWorkflowArchitectureTest {
    @Test
    void templateExecutesInvariantLifecycleInOrder() {
        List<String> phases = new ArrayList<>();
        AbstractAnalysisWorkflow workflow = workflow(AnalysisWorkflowType.DOCUMENT, phases);

        AnalysisExecutionOutcome result = workflow.execute(context(Set.of(AnalysisCapability.DOCUMENT_SEARCH)), scope());

        assertThat(phases).containsExactly("understand", "scope", "plan", "execute", "verify", "bundle", "synthesize");
        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.DOCUMENT);
    }

    @Test
    void routerSelectsCompositeForMultipleRequiredCapabilities() {
        AbstractAnalysisWorkflow document = workflow(AnalysisWorkflowType.DOCUMENT, new ArrayList<>());
        AbstractAnalysisWorkflow composite = workflow(AnalysisWorkflowType.COMPOSITE, new ArrayList<>());
        AnalysisWorkflowRouter router = new AnalysisWorkflowRouter(ctx -> ctx.intent(), List.of(document, composite));
        AnalysisContext context = context(Set.of(AnalysisCapability.DOCUMENT_SEARCH, AnalysisCapability.COMPUTATION));

        assertThat(router.route(context).workflow().type()).isEqualTo(AnalysisWorkflowType.COMPOSITE);
    }

    @Test
    void routerSelectsDomainIntelligenceForExplicitProvider() {
        AbstractAnalysisWorkflow domain = workflow(AnalysisWorkflowType.DOMAIN_INTELLIGENCE, new ArrayList<>());
        AbstractAnalysisWorkflow federated = workflow(AnalysisWorkflowType.FEDERATED_AGENT, new ArrayList<>());
        AnalysisWorkflowRouter router = new AnalysisWorkflowRouter(ctx -> ctx.intent(), List.of(domain, federated));
        AnalysisContext context = context(Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE))
            .withAttribute(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE, "group.analysis");

        assertThat(router.route(context).workflow().type()).isEqualTo(AnalysisWorkflowType.DOMAIN_INTELLIGENCE);
    }

    private AbstractAnalysisWorkflow workflow(AnalysisWorkflowType type, List<String> phases) {
        return new AbstractAnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return type; }
            @Override public String workflowId() { return "test." + type; }
            @Override public boolean supports(AnalysisContext c, AnalysisIntent i) {
                return type == AnalysisWorkflowType.COMPOSITE ? i.requiredCapabilities().size() > 1
                    : i.requiredCapabilities().size() == 1;
            }
            @Override protected AnalysisContext understand(AnalysisContext c) { phases.add("understand"); return c; }
            @Override protected AnalysisScope resolveScope(AnalysisContext c) {
                phases.add("scope"); return new AnalysisScope("t", "u", List.of(), List.of(), Map.of());
            }
            @Override protected WorkflowPlan plan(AnalysisContext c, AnalysisScope s) {
                phases.add("plan"); return new StandardWorkflowPlan("p", type, List.of(), List.of());
            }
            @Override protected WorkflowExecutionResult executePlan(AnalysisContext c, AnalysisScope s, WorkflowPlan p) {
                phases.add("execute"); return new WorkflowExecutionResult(List.of(), Map.of(), List.of());
            }
            @Override protected VerificationResult verify(AnalysisContext c, AnalysisScope s, WorkflowPlan p,
                                                          WorkflowExecutionResult e) {
                phases.add("verify"); return new VerificationResult(true, List.of(), List.of());
            }
            @Override protected EvidenceBundle evidenceBundle(AnalysisContext c, WorkflowPlan p,
                                                              WorkflowExecutionResult e, VerificationResult v) {
                phases.add("bundle"); return EvidenceBundle.empty("");
            }
            @Override protected AnalysisExecutionOutcome synthesize(AnalysisContext c, AnalysisScope s, WorkflowPlan p,
                                                          WorkflowExecutionResult e, VerificationResult v,
                                                          EvidenceBundle b) {
                phases.add("synthesize");
                return new AnalysisExecutionOutcome(null, type, p, v, b, "", Map.of());
            }
        };
    }

    private AnalysisContext context(Set<AnalysisCapability> capabilities) {
        AnalysisIntent intent = new AnalysisIntent("TEST", List.of(), capabilities, "UNSPECIFIED", true);
        return new AnalysisContext("question", scope(), "skill", List.of(), List.of(), List.of(), intent, Map.of());
    }

    private KernelDataScope scope() { return KernelDataScope.system("request-1"); }
}
