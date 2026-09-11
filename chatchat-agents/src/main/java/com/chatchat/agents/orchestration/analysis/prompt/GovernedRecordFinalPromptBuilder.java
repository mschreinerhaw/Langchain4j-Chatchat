package com.chatchat.agents.orchestration.analysis.prompt;

import com.chatchat.agents.orchestration.analysis.contract.RuntimeAnalysisResponsibilityContract;

/** Builds the compact reduce-stage prompt used after every returned dataset has been analyzed. */
public final class GovernedRecordFinalPromptBuilder {

    private GovernedRecordFinalPromptBuilder() { }

    public static String build(String userQuestion, String systemInstruction,
                               String governedRecordEvidence) {
        StringBuilder prompt = new StringBuilder();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            prompt.append("System instruction:\n").append(systemInstruction).append("\n\n");
        }
        prompt.append("""
            You are the final report author synthesizing completed Worker/Reducer analyses.
            Answer the original question in Chinese with a complete, polished Markdown report.

            Analytical task:
            1. Identify the user's decision need. Use agent_role_analysis_context attached to governed inputs
               (role name, business description, business scenarios and tags) to choose emphasis and vocabulary.
               Role context guides relevance; producer-declared semantics establish metric meaning.
            2. Review every successful non-empty dataset in a silent objective-aspect coverage matrix.
               Use workerAnalysisContext and templateMatchAnalysis to preserve scope and authorized relationships.
               Each relevant dataset with supported findings should contribute substantive analysis. Reconcile
               conflicts and overlapping populations before combining findings; retain unresolved differences.
            3. Lead with useful findings and develop the business analysis selected by the current Agent contract.
               Apply analysisMethodologyContract and analysisTree only when they were explicitly supplied. In their
               absence, choose the reasoning structure from the question, role, knowledge and returned evidence.
               Give the most important questions deeper analysis instead of listing every field.
            4. Preserve the exact values, definitions, units, measurement bases, periods and populations supplied
               by the analyses. Use a producer-returned metric directly at its declared grain. For derived measures,
               retain formula, inputs and scope from validated calculations. Proposed calculations with missing
               inputs or semantics belong in follow-up analysis. Evidence artifacts distinguish source values,
               calculations and model interpretations without prescribing repetitive labels in the report.
            5. Explain what the evidence means for the user's question. Trace each recommendation to a finding,
               its conditions and the next decision it supports. Mention each material gap once, qualifying the
               affected claim where it first appears. Keep internal review and execution details out of the report.
               Never convert a returned record count into a population count. Claim truncation, omission, or
               "at least N" only when evidence explicitly says truncated=true, sourceComplete=false, or
               pagination.hasMore=true. UNKNOWN completeness or paginationAssessed=false means unknown, not truncated.

            Presentation:
            Use adaptiveAnalysisPrompt.output as the ordered H2 section plan when supplied; translate headings
            into natural Chinese business language. Explicit user formatting takes precedence. Otherwise choose
            a concise structure covering summary, key findings, deeper analysis, actions and relevant limitations.
            Combine overlapping sections and omit empty ones. Each section adds evidence or interpretation;
            the summary compresses the body without strengthening it. Use evidence-backed Markdown tables for
            comparisons and clear units in headers. Tables support the explanation, not replace it. Select useful
            rows and disclose any selection or truncation. Existing table controls provide interactive charts.
            Keep the report understandable without claim IDs, tools, templates or workflow chronology.

            Before returning, silently reconcile values and scope across summary, detail and actions; remove
            repetition, unsupported reasoning jumps and arithmetic contradictions. Preserve bounded observations
            and meaningful business implications. Return only the complete user-facing Markdown report;
            evidence provenance and audit metadata are handled separately, not as a model-written review form.

            Original user question:
            """).append("\n").append(RuntimeAnalysisResponsibilityContract.promptSection())
            .append("\n").append(userQuestion == null ? "" : userQuestion)
            .append("\n\nGoverned dataset analysis and coverage contract:\n")
            .append(governedRecordEvidence == null ? "" : governedRecordEvidence);
        return prompt.toString();
    }
}
