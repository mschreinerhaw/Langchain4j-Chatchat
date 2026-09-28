package com.chatchat.agents.runtime.evaluation;

import java.util.Map;

public record AgentRegressionSemanticEvaluation(
    String contractVersion,
    boolean available,
    double evidenceScore,
    double answerScore,
    double reviewScore,
    double hallucinationRisk,
    boolean falseRejectLikely,
    String reason,
    Map<String, Object> raw
) {

    public static final String CONTRACT_VERSION = "agent_regression_semantic_eval_v1";

    public AgentRegressionSemanticEvaluation {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public static AgentRegressionSemanticEvaluation unavailable(String reason) {
        return new AgentRegressionSemanticEvaluation(
            CONTRACT_VERSION,
            false,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            false,
            reason,
            Map.of()
        );
    }
}
