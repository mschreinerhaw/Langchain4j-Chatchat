package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeMultiSkillWorkflowTest {
    @Test void invokesAnalysisWithSuccessfulEvidenceAndFailedBranchFacts() {
        var document = mock(AnalysisWorkflow.class);
        var tool = mock(AnalysisWorkflow.class);
        var agent = mock(AnalysisWorkflow.class);
        when(document.type()).thenReturn(AnalysisWorkflowType.DOCUMENT);
        when(tool.type()).thenReturn(AnalysisWorkflowType.TOOL);
        when(agent.type()).thenReturn(AnalysisWorkflowType.DOMAIN_INTELLIGENCE);
        for (var entry : Map.of(document, AnalysisCapability.DOCUMENT_SEARCH,
            tool, AnalysisCapability.TOOL_CALL, agent, AnalysisCapability.DOMAIN_INTELLIGENCE).entrySet())
            when(entry.getKey().supports(any(), any())).thenAnswer(call ->
                ((AnalysisIntent) call.getArgument(1)).requiredCapabilities().equals(Set.of(entry.getValue())));
        var fact = new DocumentAnalysisEvidence("document-evidence", "doc", "chunk", "document", "section",
            "citation", "retained fact", 1.0, Map.of());
        when(document.execute(any(), any())).thenReturn(new AnalysisExecutionOutcome(null, AnalysisWorkflowType.DOCUMENT,
            null, new VerificationResult(true, List.of(fact), List.of()),
            new EvidenceBundle(null, List.of(fact), List.of(), Map.of()), "", Map.of()));
        when(tool.execute(any(), any())).thenReturn(new AnalysisExecutionOutcome(null, AnalysisWorkflowType.TOOL,
            null, new VerificationResult(false, List.of(), List.of("TIMEOUT")), EvidenceBundle.empty("TIMEOUT"), "", Map.of()));
        when(agent.execute(any(), any())).thenAnswer(call -> {
            var context = (AnalysisContext) call.getArgument(0);
            var bundle = (EvidenceBundle) context.attributes().get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE);
            assertThat(bundle.evidence()).containsExactly(fact);
            assertThat(bundle.limitations()).contains("TIMEOUT");
            var analysis = new com.chatchat.common.runtime.analysis.evidence.AgentAnalysisEvidence(
                "claim", "agent", "execution", List.of(fact.evidenceId()), "Model limited conclusion", Map.of());
            return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.DOMAIN_INTELLIGENCE, null,
                new VerificationResult(true, List.of(analysis), List.of()),
                new EvidenceBundle(null, List.of(analysis), List.of(), Map.of()), "Model limited conclusion", Map.of());
        });
        @SuppressWarnings("unchecked") ObjectProvider<AnalysisWorkflow> children = mock(ObjectProvider.class);
        when(children.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of(document, tool, agent));
        var kernel = new KernelDataScope("tenant", "user", "request", null, "trace", null, Map.of());
        var context = new AnalysisContext("Analyze", kernel, "skill", List.of("doc"), List.of(), List.of(),
            new AnalysisIntent("analysis", List.of(), Set.of(AnalysisCapability.DOCUMENT_SEARCH,
                AnalysisCapability.TOOL_CALL, AnalysisCapability.DOMAIN_INTELLIGENCE), "UNSPECIFIED", true), Map.of());
        var result = new CompositeAnalysisWorkflow(children).execute(context, kernel);
        assertThat(result.synthesis()).contains("Model limited conclusion");
        assertThat(result.evidenceBundle().evidence()).contains(fact);
    }

    @Test void executesDocumentRetrievalOncePerSkillWithItsOwnScope() {
        List<AnalysisContext> seen = new ArrayList<>();
        AnalysisWorkflow document = mock(AnalysisWorkflow.class);
        when(document.type()).thenReturn(AnalysisWorkflowType.DOCUMENT);
        when(document.supports(any(), any())).thenAnswer(call ->
            ((AnalysisIntent) call.getArgument(1)).requiredCapabilities()
                .equals(Set.of(AnalysisCapability.DOCUMENT_SEARCH)));
        when(document.execute(any(), any())).thenAnswer(call -> {
            AnalysisContext context = call.getArgument(0);
            seen.add(context);
            var evidence = new DocumentAnalysisEvidence(context.skillId() + ":evidence",
                context.documentIds().get(0), "chunk", "document", "section", "citation", "text", 1.0,
                Map.of("skillId", context.skillId()));
            return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.DOCUMENT, null,
                new VerificationResult(true, List.of(evidence), List.of()),
                new EvidenceBundle(null, List.of(evidence), List.of(), Map.of()), "", Map.of());
        });
        AnalysisWorkflow agent = mock(AnalysisWorkflow.class);
        when(agent.type()).thenReturn(AnalysisWorkflowType.DOMAIN_INTELLIGENCE);
        when(agent.supports(any(), any())).thenAnswer(call ->
            ((AnalysisIntent) call.getArgument(1)).requiredCapabilities()
                .equals(Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE)));
        when(agent.execute(any(), any())).thenAnswer(call -> {
            AnalysisContext context = call.getArgument(0);
            EvidenceBundle bundle = (EvidenceBundle) context.attributes()
                .get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE);
            assertThat(bundle.evidence()).hasSize(2);
            var claim = new com.chatchat.common.runtime.analysis.evidence.AgentAnalysisEvidence(
                "claim", "agent", "execution", List.of("one:evidence", "two:evidence"),
                "combined", Map.of());
            return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.DOMAIN_INTELLIGENCE, null,
                new VerificationResult(true, List.of(claim), List.of()),
                new EvidenceBundle(null, List.of(claim), List.of(), Map.of()), "combined", Map.of());
        });
        @SuppressWarnings("unchecked") ObjectProvider<AnalysisWorkflow> children = mock(ObjectProvider.class);
        when(children.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of(document, agent));
        var workflow = new CompositeAnalysisWorkflow(children);
        KernelDataScope kernel = new KernelDataScope("tenant", "user", "request", null, "trace", null, Map.of());
        var selections = List.of(new AnalysisSkillSelection("one", List.of("doc-one"), List.of("role-one")),
            new AnalysisSkillSelection("two", List.of("doc-two"), List.of("role-two")));
        AnalysisContext context = new AnalysisContext("Analyze", kernel, "one",
            List.of("doc-one", "doc-two"), List.of(), List.of("role-one"),
            new AnalysisIntent("DOMAIN_ANALYSIS", List.of(), Set.of(AnalysisCapability.DOCUMENT_SEARCH,
                AnalysisCapability.DOMAIN_INTELLIGENCE), "UNSPECIFIED", true),
            Map.of(AnalysisContext.SKILL_SELECTIONS_ATTRIBUTE, selections));
        AnalysisExecutionOutcome outcome = workflow.execute(context, kernel);
        assertThat(outcome.verification().accepted()).isTrue();
        assertThat(seen).extracting(AnalysisContext::skillId).containsExactly("one", "two");
        assertThat(seen).extracting(AnalysisContext::documentIds)
            .containsExactly(List.of("doc-one"), List.of("doc-two"));
    }
}
