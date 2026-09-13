package com.chatchat.agents.orchestration.analysis.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveReportGenerationSpecTest {

    @Test
    void definesEvidenceBoundAdaptivePresentationWithoutAReportTemplate() {
        assertThat(AdaptiveReportGenerationSpec.promptSection()).contains(
            "Do not apply a canned business template",
            "assume industry fields, business metrics",
            "task goal, actual",
            "actual findings, data shape",
            "Organize the information silently before writing",
            "not a mandatory outline or heading list",
            "Prefer compact Markdown tables for comparable objects",
            "Use prose for meaning, trends, relationships, anomalies, causal",
            "same fact or number across summary",
            "Never present an inference as a",
            "Do not generalize a local sample into a long-term rule",
            "Preserve uncertainty naturally",
            "without limiting analytical creativity");
    }
}
