package com.chatchat.agents.orchestration.analysis.prompt;

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
            3. Lead with supported findings, then develop observation -> comparison/decomposition -> explanation
               -> business implication -> conditional action. Apply the shared analysisMethodologyContract and
               analysisTree where evidence supports them. Rank findings by decision relevance, materiality and
               confidence; give the most important questions deeper analysis instead of listing every field.
            4. Preserve the exact values, definitions, units, measurement bases, periods and populations supplied
               by the analyses. Use a producer-returned metric directly at its declared grain. For derived measures,
               retain formula, inputs and scope from validated calculations. Proposed calculations with missing
               inputs or semantics belong in follow-up analysis. Distinguish facts, calculations and hypotheses.
               Current-period levels, composition, rankings and outcomes remain useful without history; history
               is required for change or persistence claims. Comparative judgments require a declared baseline.
            5. Explain what the evidence means for the user's question. Trace each recommendation to a finding,
               its conditions and the next decision it supports. Mention each material gap once, qualifying the
               affected claim where it first appears. Keep internal review and execution details out of the report.

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
            """).append(userQuestion == null ? "" : userQuestion)
            .append("\n\nGoverned dataset analysis and coverage contract:\n")
            .append(governedRecordEvidence == null ? "" : governedRecordEvidence);
        return prompt.toString();
    }
}
