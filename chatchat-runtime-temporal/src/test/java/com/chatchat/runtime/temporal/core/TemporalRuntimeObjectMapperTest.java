package com.chatchat.runtime.temporal.core;

import com.chatchat.common.runtime.analysis.workflow.AnalysisCapability;
import com.chatchat.common.runtime.analysis.workflow.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.workflow.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.workflow.ComputationEvidence;
import com.chatchat.common.runtime.analysis.workflow.EvidenceBundle;
import com.chatchat.common.runtime.analysis.workflow.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.workflow.PlanStep;
import com.chatchat.common.runtime.analysis.workflow.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.workflow.VerificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalRuntimeObjectMapperTest {
    @Test
    void restoresPolymorphicWorkflowPlanAndEvidenceWithoutFrameworkAnnotationsInCommon() throws Exception {
        ObjectMapper mapper = TemporalRuntimeObjectMapper.configure(new ObjectMapper());
        ComputationEvidence evidence = new ComputationEvidence(
            "calc-1", "sum", List.of("input-1"), "42", Map.of());
        StandardWorkflowPlan plan = new StandardWorkflowPlan("plan-1", AnalysisWorkflowType.COMPUTATION,
            List.of(new PlanStep("1", "COMPUTE", AnalysisCapability.COMPUTATION, true, Map.of())),
            List.of(new EvidenceRequirement("COMPUTATION", true, 1, "verified")));
        AnalysisExecutionOutcome source = new AnalysisExecutionOutcome(null,
            AnalysisWorkflowType.COMPUTATION, plan,
            new VerificationResult(true, List.of(evidence), List.of()),
            new EvidenceBundle(null, List.of(evidence), List.of(), Map.of()), "42", Map.of());

        AnalysisExecutionOutcome restored = mapper.readValue(
            mapper.writeValueAsString(source), AnalysisExecutionOutcome.class);

        assertThat(restored.plan()).isInstanceOf(StandardWorkflowPlan.class);
        assertThat(restored.evidenceBundle().evidence()).singleElement()
            .isInstanceOf(ComputationEvidence.class);
        assertThat(restored.verification().acceptedEvidence()).singleElement()
            .isInstanceOf(ComputationEvidence.class);
    }
}
