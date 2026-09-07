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
            Separate direct evidence, declared proxies, observed behavior, external context and event narratives.
            Measures with different definitions, units, populations or temporal bases are not interchangeable.
            A snapshot does not establish a change, sequence, cause, intent or persistent behavior.
            Context and events cannot replace missing direct evidence or validated proxies for a directional claim.
            Compare dates, population, denominator and units before combining statistics. A sample is not the population.
            Do not infer categories from identifiers, or convert absent measurements into zero values.
            Explain concentration by verified category only when classifications are available.
            Put consolidated missing-data details in LIMITATION once. ACTION ranks follow-up data by the decision
            it would resolve; conditional actions require explicit evidence and assumptions.
            Findings are evidence-binding metadata, not the report body. Choose dataRef only from the runtime report data catalog,
            bound to the finding's basisClaimIds. Choose visualizationIntent from RANK, CONTRIBUTION or KPI according
            to the executed operation. The model selects the question-relevant analysis from declared field semantics;
            the Runtime never invents SUM, AVG, ratio, denominator, weighting, time window or aggregation meaning.
            For a derived value, select and explain only a semantic-contract-authorized calculation and cite its
            Runtime result through dataRef. A new formula may be proposed with its inputs and intended meaning, but
            remains an explicitly unverified analysis direction until the Runtime can execute and audit it.
            Never fabricate chart data or table rows. Runtime binds optional visualizations to verified data.
            Write plain prose in finding.text for machine-readable indexing. In reportMarkdown, author the complete
            report with your own headings, evidence-backed Markdown tables, comparisons and connected explanations
            where they help answer the question. Runtime never composes the report from finding fields.
            Every primary conclusion requires both an explanation and a verifiable data expression. An admitted claim
            with returned-record references and supporting values is a verifiable evidence expression even when no
            computed dataRef exists. In that case leave dataRef empty and publish a grounded text/metric finding; a
            chart is presentation enrichment, not a publication gate. Missing dimensions limit only dependent claims.
            If at least one usable record exists, analyze what it supports and state scope limits instead of refusing
            the whole analysis. Unsupported visualization intents fall back to verified data tables.
            Before returning, make the report self-consistent: use one value, unit, period, population and definition
            for each metric; keep interpretation no stronger than observation and implication no stronger than
            interpretation; qualify a limitation where the claim first appears; ensure every action traces to a
            finding; remove duplicate findings, empty sections and internal workflow language. The executive summary,
            detail, limitations and actions must be readable together without contradiction or Runtime context.
            Only finding.text must avoid embedded section headings; this restriction never applies to reportMarkdown.
            """;
    }
}
