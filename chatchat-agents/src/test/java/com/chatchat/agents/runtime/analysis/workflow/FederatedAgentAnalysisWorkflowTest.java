package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.agents.runtime.federation.ComputeNodeRouter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentCollaborationPlan;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
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
    @Test void collaborationRunsDependentAgentsAndMergesTheirEvidence() {
        java.util.List<AgentExecutionRequest> requests = new java.util.ArrayList<>();
        ExecutionUnit<AgentExecutionRequest, AgentExecutionOutcome> agent = new ExecutionUnit<>() {
            @Override public ComputeNodeType nodeType() { return ComputeNodeType.AGENT; }
            @Override public Class<AgentExecutionRequest> inputType() { return AgentExecutionRequest.class; }
            @Override public Class<AgentExecutionOutcome> outputType() { return AgentExecutionOutcome.class; }
            @Override public AgentExecutionOutcome execute(AgentExecutionRequest input, KernelDataScope scope) {
                requests.add(input);
                String taskId = String.valueOf(input.metadata().get(AgentExecutionRequest.COLLABORATION_TASK_METADATA_KEY));
                String evidenceId = "second".equals(taskId) ? "first:claim" : "seed";
                return new AgentExecutionOutcome(null, input.executionId(),
                    String.valueOf(input.metadata().get(AgentExecutionRequest.TARGET_AGENT_METADATA_KEY)),
                    AgentExecutionOutcome.Status.COMPLETED,
                    List.of(new AgentExecutionOutcome.GroundedClaim("claim", taskId + " conclusion",
                        List.of(evidenceId), .9)), List.of(), List.of(), List.of(), "", "", Map.of(), Map.of());
            }
        };
        AgentCollaborationPlan collaboration = new AgentCollaborationPlan(List.of(
            new AgentCollaborationPlan.Task("first", "local.agent", com.chatchat.common.runtime.capability.CapabilityId.parse("finance.test.v1"),
                "Prepare domain interpretation", AgentExecutionMode.DOMAIN_INFERENCE, List.of()),
            new AgentCollaborationPlan.Task("second", "group.agent", com.chatchat.common.runtime.capability.CapabilityId.parse("finance.test.v1"),
                "Review the first interpretation", AgentExecutionMode.AGENTIC_EXECUTION, List.of("first"))));
        var seed = new ProjectedAnalysisEvidence("seed", AnalysisCapability.DOCUMENT_SEARCH,
            "approved facts", Map.of("sourceType", "DocumentAnalysisEvidence"));
        AnalysisContext context = context().withAttribute(AgentCollaborationPlan.CONTEXT_ATTRIBUTE, collaboration)
            .withAttribute(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE,
                new EvidenceBundle(null, List.of(seed), List.of(), Map.of()));
        var result = new FederatedAgentAnalysisWorkflow(new ComputeNodeRouter(List.of(agent)))
            .execute(context, context.kernelScope());

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).evidence().evidence()).extracting(value -> value.evidenceId())
            .contains("first:claim");
        assertThat(requests.get(1).executionMode()).isEqualTo(AgentExecutionMode.AGENTIC_EXECUTION);
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).extracting(value -> value.evidenceId())
            .contains("seed", "first:claim", "second:claim");
        assertThat(result.synthesis()).contains("first conclusion", "second conclusion");
        assertThat(result.metadata()).containsEntry("collaboration", true)
            .containsEntry("completedTaskCount", 2);
    }

    @Test void invalidCollaborationDependencyIsRejectedBeforeExecution() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new AgentCollaborationPlan(List.of(
            new AgentCollaborationPlan.Task("second", "group.agent",
                com.chatchat.common.runtime.capability.CapabilityId.parse("finance.test.v1"), "Analyze",
                AgentExecutionMode.DOMAIN_INFERENCE, List.of("first"))))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedTaskKeepsVerifiedPartialResultAndSkipsItsDependents() {
        ExecutionUnit<AgentExecutionRequest, AgentExecutionOutcome> agent = new ExecutionUnit<>() {
            @Override public ComputeNodeType nodeType() { return ComputeNodeType.AGENT; }
            @Override public Class<AgentExecutionRequest> inputType() { return AgentExecutionRequest.class; }
            @Override public Class<AgentExecutionOutcome> outputType() { return AgentExecutionOutcome.class; }
            @Override public AgentExecutionOutcome execute(AgentExecutionRequest input, KernelDataScope scope) {
                String taskId = String.valueOf(input.metadata().get(AgentExecutionRequest.COLLABORATION_TASK_METADATA_KEY));
                if (!"first".equals(taskId)) throw new IllegalStateException("remote unavailable");
                return new AgentExecutionOutcome(null, input.executionId(), "local.agent",
                    AgentExecutionOutcome.Status.COMPLETED,
                    List.of(new AgentExecutionOutcome.GroundedClaim("claim", "first finding",
                        List.of("seed"), .9)), List.of(), List.of(), List.of(), "", "", Map.of(), Map.of());
            }
        };
        var capability = com.chatchat.common.runtime.capability.CapabilityId.parse("finance.test.v1");
        var collaboration = new AgentCollaborationPlan(List.of(
            new AgentCollaborationPlan.Task("first", "local.agent", capability, "Interpret facts",
                AgentExecutionMode.DOMAIN_INFERENCE, List.of()),
            new AgentCollaborationPlan.Task("second", "group.agent", capability, "Review",
                AgentExecutionMode.DOMAIN_INFERENCE, List.of("first")),
            new AgentCollaborationPlan.Task("third", "external.agent", capability, "Summarize review",
                AgentExecutionMode.DOMAIN_INFERENCE, List.of("second"))));
        var seed = new ProjectedAnalysisEvidence("seed", AnalysisCapability.DOCUMENT_SEARCH,
            "approved facts", Map.of("sourceType", "DocumentAnalysisEvidence"));
        var context = context().withAttribute(AgentCollaborationPlan.CONTEXT_ATTRIBUTE, collaboration)
            .withAttribute(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE,
                new EvidenceBundle(null, List.of(seed), List.of(), Map.of()));

        var result = new FederatedAgentAnalysisWorkflow(new ComputeNodeRouter(List.of(agent)))
            .execute(context, context.kernelScope());

        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.synthesis()).isEqualTo("first finding");
        assertThat(result.metadata()).containsEntry("completedTaskCount", 1);
        assertThat(result.metadata().get("taskStates")).isEqualTo(Map.of(
            "first", "COMPLETED", "second", "FAILED", "third", "SKIPPED_DEPENDENCY"));
        assertThat(result.evidenceBundle().limitations()).anyMatch(value -> value.contains("second"));
    }
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
