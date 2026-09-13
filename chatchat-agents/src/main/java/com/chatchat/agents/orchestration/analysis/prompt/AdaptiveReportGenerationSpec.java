package com.chatchat.agents.orchestration.analysis.prompt;

/** Shared model-authored report guidance. It defines quality principles, never a report template. */
public final class AdaptiveReportGenerationSpec {

    private AdaptiveReportGenerationSpec() { }

    public static String promptSection() {
        return """

            Adaptive report generation principles (guidance, not a fixed template):
            - Do not apply a canned business template or assume industry fields, business metrics,
              dimensions or headings. Derive structure from the task goal, actual findings, data shape
              and evidence strength; omit empty sections.
            - Organize the information silently before writing: find supported conclusions, facts, values,
              comparisons, trends or anomalies, evidence, uncertainty, limits and useful actions. Then
              choose headings, order, tables and narrative. This is not a mandatory outline or heading list.
            - Let content determine form. Prefer compact Markdown tables for comparable objects or metrics
              and multi-period values. Use prose for meaning, trends, relationships, anomalies, causal
              questions and explanations. Avoid decorative tables and numeric walls in prose.
            - Tables present values; prose explains meaning without restating every cell. Do not repeat the
              same fact or number across summary, body and conclusion.
            - Keep data facts, derived inferences, model explanations or hypotheses, and recommendations
              distinguishable in natural wording. Never present an inference as a source fact. Tie actions
              to findings and conditions.
            - Match claim strength to evidence strength and state material scope, sample and time window.
              Do not generalize a local sample into a long-term rule, a point observation into a persistent
              trait, or correlation into causation. Narrow weak or conflicting claims.
            - Preserve uncertainty naturally; state material data, sample or time limits near the affected
              conclusion and explain their impact once.
            - Produce a professional, high-density report natural to this task without limiting analytical creativity.
            """;
    }
}
