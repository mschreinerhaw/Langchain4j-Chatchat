package com.chatchat.agents.orchestration.analysis.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedRecordFinalPromptBuilderTest {
    @Test
    void compactPromptPreservesAnalyticalDepthAndMarkdownOwnership() {
        String prompt = GovernedRecordFinalPromptBuilder.build(
            "分析客户交易偏好", "遵守业务口径", "assets summary\norders summary\nprofit summary");
        assertThat(prompt).contains(
            "workerAnalysisContext and templateMatchAnalysis", "agent_role_analysis_context",
            "every successful non-empty dataset", "objective-aspect coverage matrix",
            "develop the business analysis selected by the current Agent contract",
            "producer-returned metric directly at its declared grain",
            "formula, inputs and scope", "without prescribing repetitive labels",
            "The model owns analytical reasoning", "active Agent role",
            "not a requirement to expose a confidence label",
            "adaptiveAnalysisPrompt", "not a mandatory",
            "Choose the report structure, headings, order, depth and narrative flow yourself",
            "Write at a depth", "proportionate to the usable evidence",
            "inspect contrary records before using words such as all",
            "explicit user", "formatting takes precedence", "evidence-backed Markdown tables",
            "sole author of the final report", "Runtime will not",
            "combine explanatory prose", "You decide the useful tables",
            "adaptive reasoning loop", "not as mandatory",
            "complete user-facing Markdown report", "orders summary", "遵守业务口径")
            .doesNotContain("machine-readable output shape", "claimAssessments", "anomaly degree multiplied");
        assertThat(prompt.length()).isLessThan(9000);
    }

    @Test
    void nullInputsDoNotLeakNullLiterals() {
        assertThat(GovernedRecordFinalPromptBuilder.build(null, null, null))
            .contains("complete user-facing Markdown report").doesNotContain("null");
    }
}
