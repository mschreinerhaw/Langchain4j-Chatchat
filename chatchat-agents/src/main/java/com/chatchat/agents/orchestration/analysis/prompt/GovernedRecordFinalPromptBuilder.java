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
        prompt.append("Model decides. Runtime executes. User judges. You own the analysis and report organization. Apply supplied Skills and role context, distinguish evidence from interpretation, and return the user-facing Markdown report.\n")
            .append("Question:\n").append(userQuestion == null ? "" : userQuestion)
            .append("\nAvailable evidence:\n").append(governedRecordEvidence == null ? "" : governedRecordEvidence);
        return prompt.toString();
    }
}
