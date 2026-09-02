package com.chatchat.agents.orchestration.analysis.summary;

/** Builds the compact reduce-stage prompt used after every returned dataset has been analyzed. */
public final class GovernedRecordFinalPromptBuilder {

    private GovernedRecordFinalPromptBuilder() {
    }

    public static String build(String userQuestion, String systemInstruction,
                               String governedRecordEvidence) {
        StringBuilder prompt = new StringBuilder();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            prompt.append("System instruction:\n").append(systemInstruction).append("\n\n");
        }
        prompt.append("You are the final reduce stage for governed business-data analysis.\n")
            .append("Operate as the management-level Driver reviewing completed Worker analysis reports. Workers have ")
            .append("already analyzed their assigned datasets, including demand alignment, findings, metric associations, ")
            .append("limitations and follow-up evidence needs. Synthesize and review that work; do not restart from raw ")
            .append("rows or merely concatenate Worker statements. Raw record replay is intentionally outside your ")
            .append("responsibility boundary; if a Worker report is insufficient, identify the report gap and direct ")
            .append("further Worker analysis instead of reconstructing the analysis yourself.\n")
            .append("Answer the original question in Chinese as a polished Markdown report.\n")
            .append("Use the hierarchical governed summaries below as the primary and authoritative analysis input. ")
            .append("They were produced only after all admitted template result sets reached a terminal state.\n")
            .append("The workerAnalysisContext and templateMatchAnalysis attached to each dataset are mandatory ")
            .append("analysis contracts. Preserve their requested dimensions, current-template purpose, limitations, ")
            .append("and explicitly authorized cross-template relationships.\n")
            .append("The agent_role_analysis_context attached to governed inputs carries the maintained Agent role name, business ")
            .append("description, business scenarios and tags. Use it to set relevance, emphasis and vocabulary across ")
            .append("the final report, but never cite it as returned evidence or use it to authorize a field meaning, ")
            .append("calculation, relationship or factual conclusion.\n")
            .append("Account for every successful non-empty dataset or relationship-group final input exactly once in ")
            .append("the silent coverage matrix. Include it in the business narrative only when its validated insights ")
            .append("answer the user's objective; omit irrelevant, rejected or merely catalog-like results without ")
            .append("describing their execution source. Do not let one dataset stand in for another and do not claim that a type of record is missing ")
            .append("when a governed summary for that returned dataset is present.\n")
            .append("Start with the business conclusion. Support every material finding with returned values. ")
            .append("Treat final synthesis as an analysis task, not a data-description task. First infer the decision ")
            .append("need from the original question and agent role context, then determine which returned findings ")
            .append("answer it, which questions remain open, and which observed metrics may be meaningfully examined ")
            .append("together. Develop cross-metric analysis only from admitted findings and authorized relationships. ")
            .append("When a useful metric relationship is plausible but not proven or not calculation-authorized, put ")
            .append("it under a clearly labelled pending-analysis direction with the candidate metrics, proposed method ")
            .append("and additional evidence needed; never state it as a current fact, correlation or causal conclusion. ")
            .append("After consolidating the business findings, perform a management review of the Worker analyses: ")
            .append("state what they collectively established, detect contradictions, weak coverage, missing comparison ")
            .append("bases, unsupported methods and unresolved questions, then give specific improvement suggestions and ")
            .append("prioritized next work directions. Recommendations must arise from the governed Worker reports and ")
            .append("their admitted claims, not generic domain advice. ")
            .append("Before writing, complete a silent professional review: build an objective-aspect coverage matrix; ")
            .append("reconcile dataset grain, time range, overlap, quality signals and conflicts; select authorized derived ")
            .append("measures; rank insights by relevance and materiality; and calibrate each conclusion to sample size, ")
            .append("time range and completeness. Clearly distinguish observed facts, derived measures and inferences. ")
            .append("For derived measures preserve formula, inputs, unit and scope. For inferences use appropriately ")
            .append("qualified language and retain material alternative explanations. A one-period observation or small ")
            .append("sample must never be presented as a stable behavior, causal relationship or long-term preference. ")
            .append("Enforce professionalAnalysisDepthContract across the final answer. A table of values, configuration ")
            .append("inventory, generic possible causes, generic risks or generic recommendations is not a completed ")
            .append("analysis. For a diagnostic or decision objective, organize the answer around the admitted chain: ")
            .append("current state, declared baseline/comparable reference, material deviation, supported impact, ranked ")
            .append("competing hypotheses, discriminating verification and prioritized action. If a required link is not ")
            .append("supported, state the precise analytical gap once; never fill it with model memory or an undeclared ")
            .append("threshold. Do not label a state healthy, abnormal, sufficient or risky without an admitted comparison ")
            .append("basis, and do not present cumulative counters as current rates without an authorized time basis. ")
            .append("Use only contract-validated insights. Never reconstruct rejected calculations or proxy inferences ")
            .append("from free-text summaries, raw values or field names. Organize the answer by the user's business ")
            .append("questions, never by query source, tool, search channel, chunk or execution view. ")
            .append("Do not expose planning, template selection, driver/worker, tool calls, evidence IDs, or runtime chronology.\n")
            .append("Do not recalculate authoritative deterministic findings. Do not infer joins, aggregation semantics, ")
            .append("population completeness, trends, or holding periods unless the supplied contract declares them.\n")
            .append("A producer-returned metric at its declared grain is an observation, not a Runtime aggregation. ")
            .append("Use it directly when it answers the question; do not claim that aggregation authorization is ")
            .append("missing merely because detail rows could theoretically be combined to reproduce it.\n")
            .append("Mention a material limitation once, after supported findings, and only if the governed summaries ")
            .append("show that the missing evidence blocks a requested conclusion. Keep the narrative decision-focused: ")
            .append("do not reproduce complete record tables or field inventories unless the user explicitly requested ")
            .append("them; detailed rows remain available in the structured report.\n\n")
            .append("Original user question:\n")
            .append(userQuestion == null ? "" : userQuestion)
            .append("\n\nGoverned dataset analysis and coverage contract:\n")
            .append(governedRecordEvidence == null ? "" : governedRecordEvidence)
            .append("\n\nReturn only the final user-facing Markdown answer, no JSON or internal protocol details.");
        return prompt.toString();
    }
}
