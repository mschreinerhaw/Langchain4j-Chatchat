package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

/** Semantic instructions supplementing the runtime finding structure and numeric admission checks. */
final class AnalysisSynthesisContract {
    private AnalysisSynthesisContract() { }

    /**
     * Governs the reasoning order of a model-authored report without prescribing its headings or prose.
     */
    static String narrativeCoherenceInstruction() {
        return """
            Presentation is free-form, but facts, evidence scope and reasoning order are constrained.
            Use adaptiveAnalysisPrompt.output and sectionTitles when present as an ordered business scaffold.
            Develop each key finding through observed facts, supported structure or comparison, bounded business
            implications and conditional next actions. Put the most decision-relevant findings first.
            Optional visualizationSpec blocks are presentation declarations; Runtime independently recomputes and
            validates their selected data. Their acceptance does not certify the report's business interpretation.
            Internally establish the decision question and evidence scope first; then align each measure's
            definition, unit, period, population and measurement basis; then derive observations,
            calculations and comparisons; then distinguish interpretation and plausible alternatives;
            finally state bounded implications, actions and limitations.
            Every material conclusion must form a traceable chain from conclusion to evidence to reasoning
            to its boundary or implication. Use the same definition, unit, period, population and value for
            a measure throughout the report, and never give one fact conflicting values.
            Any summary may compress the supporting analysis, but must not strengthen, broaden or contradict it;
            the supporting analysis must substantiate every material summary judgment. Treat intent, causality and persistent
            behavior as hypotheses unless the supplied evidence establishes them. Recommendations must trace
            to findings and state material conditions. Put a qualification where the affected claim first
            appears; a later limitation section cannot repair an earlier overstatement.
            Before returning, silently check the whole report for contradictions, unsupported reasoning jumps,
            duplicate conclusions, missing scope, arithmetic inconsistency and broken transitions. Resolve them
            before returning. Do not expose this internal checklist or use it as a required report outline.
            """;
    }

}
