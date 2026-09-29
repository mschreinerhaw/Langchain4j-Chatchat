package com.chatchat.common.runtime.capability;

import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CapabilityWorkflowProtocolTest {
    @Test void fourFamiliesHaveRequiredCapabilitiesButOnlyOptionalDomainSkills() {
        for (var family : RuntimeWorkflowFamily.values()) {
            var plan = CapabilityWorkflowPlan.forFamily(family);
            assertThat(plan.requiredCapabilities()).isNotEmpty().doesNotContain("domain_guidance");
            var ids = plan.requirements().stream().map(CapabilityWorkflowPlan.Requirement::capability).toList();
            for (var requirement : plan.requirements()) assertThat(ids).containsAll(requirement.dependsOn());
        }
    }
    @Test void routesOnlyAfterProblemAnalysisAndRejectsMissingPlan() {
        var router = new CapabilityWorkflowRouter();
        for (var intent : ProblemAnalysisPlan.Intent.values()) {
            var plan = new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY, "goal", "subject", "domain", "explanation",
                List.of(new ProblemAnalysisPlan.Task("task", intent, List.of("evidence"), "result")), "");
            assertThat(router.requiredWorkflows(plan)).hasSize(1).contains(router.route(plan));
        }
        assertThatThrownBy(() -> router.route(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.route(ProblemAnalysisPlan.unavailable())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aMissingRequiredCapabilityCannotBeCompletedButOptionalEnhancementDoesNotBlock() {
        assertThat(new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER, "", List.of("verify"), List.of(), true).publicStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER, "", List.of(), List.of("domain_guidance"), true).publicStatus()).isEqualTo("SUCCESS");
        assertThat(new WorkflowOutcome(WorkflowOutcome.Type.NO_EXECUTABLE_PLAN, "", List.of(), List.of(), false).publicStatus()).isEqualTo("NO_PRESENTABLE_RESULT");
    }
}
