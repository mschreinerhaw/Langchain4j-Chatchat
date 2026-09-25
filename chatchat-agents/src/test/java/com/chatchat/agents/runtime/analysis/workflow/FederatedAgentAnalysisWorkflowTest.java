package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.agents.runtime.federation.ComputeNodeRouter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ProjectedAnalysisEvidence;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FederatedAgentAnalysisWorkflowTest {
    @Test void finalBundleContainsSupplementedEvidenceAndAcceptedClaim() {
        var supplement = new ProjectedAnalysisEvidence("skill-e1", AnalysisCapability.DOCUMENT_SEARCH,
            "approved summary", Map.of("sourceType", "DocumentAnalysisEvidence"));
        var bundle = new EvidenceBundle(null, List.of(supplement), List.of(), Map.of());
        var workflow = workflow(AgentExecutionOutcome.Status.COMPLETED, bundle);
        var result = workflow.execute(context(), context().kernelScope());

        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).extracting(value -> value.evidenceId())
            .contains("skill-e1", "claim-1");
        assertThat(result.synthesis()).isEqualTo("grounded conclusion");
        assertThat(result.metadata()).containsEntry("judgeDecision", "ACCEPT");
    }

    @Test void rejectedAgentClaimNeverBecomesFinalSynthesis() {
        var result = workflow(AgentExecutionOutcome.Status.REPLAN_REQUIRED,
            EvidenceBundle.empty("none")).execute(context(), context().kernelScope());

        assertThat(result.verification().accepted()).isFalse();
        assertThat(result.synthesis()).isBlank();
        assertThat(result.evidenceBundle().evidence()).isEmpty();
        assertThat(result.metadata()).containsEntry("judgeDecision", "REPLAN");
    }

    private FederatedAgentAnalysisWorkflow workflow(AgentExecutionOutcome.Status status, EvidenceBundle bundle) {
        ExecutionUnit<AgentExecutionRequest, AgentExecutionOutcome> agent = new ExecutionUnit<>() {
            @Override public ComputeNodeType nodeType() { return ComputeNodeType.AGENT; }
            @Override public Class<AgentExecutionRequest> inputType() { return AgentExecutionRequest.class; }
            @Override public Class<AgentExecutionOutcome> outputType() { return AgentExecutionOutcome.class; }
            @Override public AgentExecutionOutcome execute(AgentExecutionRequest input, KernelDataScope scope) {
                return new AgentExecutionOutcome(null, input.executionId(), "group.agent", status,
                    List.of(new AgentExecutionOutcome.GroundedClaim("claim-1", "grounded conclusion",
                        List.of("skill-e1"), .9)), List.of(), List.of(), List.of(), "", "", Map.of(),
                    Map.of("runtimeEvidenceBundle", bundle));
            }
        };
        return new FederatedAgentAnalysisWorkflow(new ComputeNodeRouter(List.of(agent)));
    }

    private AnalysisContext context() {
        KernelDataScope kernel = KernelDataScope.system("request-1");
        return new AnalysisContext("Analyze", kernel, "skill-1", List.of(), List.of(), List.of(),
            new AnalysisIntent("ANALYZE", List.of(), Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE),
                "UNSPECIFIED", true),
            Map.of(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE, "finance.test.v1"));
    }
}
