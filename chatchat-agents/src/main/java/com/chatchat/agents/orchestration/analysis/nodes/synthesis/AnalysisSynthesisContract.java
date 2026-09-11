package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.contract.AnalyticalReasoningArcContract;

/** Semantic instructions supplementing the runtime finding structure and numeric admission checks. */
final class AnalysisSynthesisContract {
    private AnalysisSynthesisContract() { }

    /**
     * Governs the reasoning order of a model-authored report without prescribing its headings or prose.
     */
    static String narrativeCoherenceInstruction() {
        return """
            Presentation and analytical reasoning are owned by the model under the active Agent contract.
            Treat adaptiveAnalysisPrompt as a question-specific analytical brief, not a mandatory outline.
            Choose and organize the report yourself, develop useful business meaning from the supplied findings,
            and put the most decision-relevant analysis first.
            Optional visualizationSpec blocks are presentation declarations; Runtime independently recomputes and
            validates their selected data. Their acceptance does not certify the report's business interpretation.
            Preserve the evidence links and producer-declared identities, values and semantics in analysis artifacts.
            Runtime uses those links for audit and protocol validation, not to impose a domain-specific reasoning style.
            Confidence labels, alternatives and qualifications are optional unless the active Agent contract requests them.
            Before returning, silently check the whole report for contradictions, unsupported reasoning jumps,
            duplicate conclusions, arithmetic inconsistency and broken transitions. Resolve them
            before returning. Do not expose this internal checklist or use it as a required report outline.
            """ + AnalyticalReasoningArcContract.promptSection();
    }

}
