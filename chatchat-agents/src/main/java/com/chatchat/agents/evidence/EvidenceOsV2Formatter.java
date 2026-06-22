package com.chatchat.agents.evidence;

public class EvidenceOsV2Formatter {

    public String format(EvidenceExecutionReport report) {
        if (report == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Evidence OS execution (contractVersion=")
            .append(report.contractVersion())
            .append("):\n");
        builder.append("decision: ").append(report.decision()).append('\n');
        EvidenceAnswerContract contract = report.answerContract();
        if (contract != null) {
            builder.append("answerContract: ").append(contract.contractVersion()).append('\n');
            builder.append("fromGraphOnly: ").append(contract.fromGraphOnly()).append('\n');
            builder.append("executable: ").append(contract.executable()).append('\n');
            if (!contract.evidencePath().isEmpty()) {
                builder.append("evidencePath: ").append(String.join(" -> ", contract.evidencePath())).append('\n');
            }
            if (!contract.sqlLineage().isEmpty()) {
                builder.append("sqlLineage: ").append(String.join(", ", contract.sqlLineage())).append('\n');
            }
        }
        if (report.selectedPath() != null) {
            builder.append("selectedPathScore: ").append(report.selectedPath().score()).append('\n');
            builder.append("selectedPathExecutable: ").append(report.selectedPath().executable()).append('\n');
            builder.append("selectedPathHasTrustedSQL: ").append(report.selectedPath().hasTrustedSql()).append('\n');
        }
        if (!report.reasons().isEmpty()) {
            builder.append("reasons: ").append(String.join("; ", report.reasons())).append('\n');
        }
        builder.append("runtimeRules: answer must be derived from evidencePath; no external generation; no SQL answer unless EXECUTION_VERIFIED.");
        return builder.toString().trim();
    }
}
