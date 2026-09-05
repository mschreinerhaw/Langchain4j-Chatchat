package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

/** Semantic instructions supplementing the runtime finding structure and numeric admission checks. */
final class AnalysisSynthesisContract {
    private AnalysisSynthesisContract() { }

    static String instruction() {
        return """
            Organize the synthesis around one decision question in demandAnalysis.decisionGoal.
            Review and rank findings by relevance, evidence strength and business impact; deduplicate them.
            CORE answers that question, distinguishing confirmed facts, supported inference and unresolved judgment.
            DEEP_DIVE findings explain What -> Why -> So What -> Now What using question, baseline,
            comparison, driver, implication and confidence. Leave unsupported fields empty; never invent a baseline
            or a cause to fill the structure. Label explanations as hypotheses unless causality is established.
            Keep CORE concise; put detailed reasoning in DEEP_DIVE without repeating the same paragraph.
            Separate direct evidence, qualified proxies, trading behavior, external context and event narratives.
            Trading turnover cannot establish net inflow. Static size cannot establish subscriptions or liquidity.
            Share changes and asset-value changes have different units; asset-value changes also reflect repricing.
            External fund flows cannot establish flows into the target market or product universe.
            Context and events cannot replace missing direct evidence or validated proxies for a directional claim.
            Compare dates, universe, denominator and units before combining statistics. A sample is not the whole market.
            Do not infer product categories from identifiers, or infer zero flows from a suspension announcement.
            Explain concentration by verified category only when classifications are available.
            Put consolidated missing-data details in LIMITATION once. ACTION ranks follow-up data by the decision
            it would resolve; conditional monitoring is appropriate when allocation claims are unsupported.
            Each finding is an Analytical Insight Block. Choose dataRef only from the runtime report data catalog,
            bound to the finding's basisClaimIds. Choose visualizationIntent from RANK, CONTRIBUTION or KPI according
            to the executed operation. Never supply chart data, table rows, units, or computed metrics yourself.
            The runtime planner chooses the chart and the composer binds chart, table, metric, observation,
            interpretation, implication, confidence and evidence. Write plain prose in text; no Markdown data tables.
            Every primary conclusion requires both an explanation and a verifiable data expression. If no matching
            computed data exists, leave dataRef empty: the runtime will publish a data-status block, not a primary
            evidence-backed business conclusion. Unsupported visualization intents fall back to verified data tables.
            Findings must not contain embedded section headings or dangling citation brackets.
            """;
    }
}
