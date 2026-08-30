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
            .append("Answer the original question in Chinese as a polished Markdown report.\n")
            .append("Use the hierarchical governed summaries below as the primary and authoritative analysis input. ")
            .append("They were produced only after all admitted template result sets reached a terminal state.\n")
            .append("The workerAnalysisContext and templateMatchAnalysis attached to each dataset are mandatory ")
            .append("analysis contracts. Preserve their requested dimensions, current-template purpose, limitations, ")
            .append("and explicitly authorized cross-template relationships.\n")
            .append("Cover every successful non-empty dataset or relationship-group final input exactly once. ")
            .append("Do not let one dataset stand in for another and do not claim that a type of record is missing ")
            .append("when a governed summary for that returned dataset is present.\n")
            .append("Start with the business conclusion. Support every material finding with returned values. ")
            .append("Do not expose planning, template selection, driver/worker, tool calls, evidence IDs, or runtime chronology.\n")
            .append("Do not recalculate authoritative deterministic findings. Do not infer joins, aggregation semantics, ")
            .append("population completeness, trends, or holding periods unless the supplied contract declares them.\n")
            .append("Mention a material limitation once, after supported findings, and only if the governed summaries ")
            .append("show that the missing evidence blocks a requested conclusion.\n\n")
            .append("Original user question:\n")
            .append(userQuestion == null ? "" : userQuestion)
            .append("\n\nGoverned dataset analysis and coverage contract:\n")
            .append(governedRecordEvidence == null ? "" : governedRecordEvidence)
            .append("\n\nReturn only the final user-facing Markdown answer, no JSON or internal protocol details.");
        return prompt.toString();
    }
}
