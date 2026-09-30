package com.chatchat.agents.orchestration.analysis.nodes.synthesis;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SkillSynthesisContextTest {
    @Test void finalReportReceivesSameVersionedMethodsSeparatelyFromEvidence() {
        var context = SkillAnalysisContext.create("APPLIED", List.of(Map.of("id", "s", "version", "v1")),
            Map.of("REPORT", List.of("Disclose uncertainty"), "VALIDATION", List.of("Validate denominator")));
        var synthesis = new AnalysisSynthesisContext().build(List.of(), List.of(), Map.of(SkillAnalysisContext.ATTRIBUTE, context), Map.of());
        assertThat(synthesis).containsEntry(SkillAnalysisContext.ATTRIBUTE, context);
        assertThat(synthesis.get("nodeInputs").toString()).doesNotContain("Disclose uncertainty");
    }
}
