package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentRegressionLlmEvaluator {

    private final ObjectMapper objectMapper;

    public AgentRegressionLlmEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public AgentRegressionSemanticEvaluation evaluate(ChatModel chatModel,
                                                       AgentRegressionCase testCase,
                                                       AgentRegressionObservation observation,
                                                       AgentRegressionResult deterministicResult) {
        if (chatModel == null) {
            return AgentRegressionSemanticEvaluation.unavailable("LLM evaluator unavailable");
        }
        String raw = chatModel.chat(buildPrompt(testCase, observation, deterministicResult));
        Map<String, Object> payload = parseJsonObject(raw);
        if (payload.isEmpty()) {
            return AgentRegressionSemanticEvaluation.unavailable("LLM evaluator returned invalid JSON");
        }
        return new AgentRegressionSemanticEvaluation(
            AgentRegressionSemanticEvaluation.CONTRACT_VERSION,
            true,
            bounded(decimal(payload.get("evidence_score"), deterministicResult.evidence().score())),
            bounded(decimal(payload.get("answer_score"), deterministicResult.answer().valid() ? 1.0D : 0.0D)),
            bounded(decimal(payload.get("review_score"), deterministicResult.review().score())),
            bounded(decimal(payload.get("hallucination_risk"), 0.0D)),
            bool(payload.get("false_reject_likely"), deterministicResult.review().falseReject()),
            string(payload.get("reason")),
            payload
        );
    }

    String buildPrompt(AgentRegressionCase testCase,
                       AgentRegressionObservation observation,
                       AgentRegressionResult deterministicResult) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the semantic evaluator for an Agent Runtime regression test.\n");
        prompt.append("You are auxiliary only. Do not make a hard CI pass/fail decision.\n");
        prompt.append("Judge semantic reasonableness and produce calibrated scores from 0.0 to 1.0.\n");
        prompt.append("Partial multi-chunk document evidence can be sufficient when it supports synthesis and missing pieces are stated clearly.\n");
        prompt.append("A false reject is likely when retrieval hit and evidence is useful, but review rejected because the evidence was not already a complete final answer.\n");
        prompt.append("Return strict JSON only with this shape:\n");
        prompt.append("{\"evidence_score\":0.0,\"answer_score\":0.0,\"review_score\":0.0,\"hallucination_risk\":0.0,\"false_reject_likely\":false,\"reason\":\"brief\"}\n\n");
        prompt.append("Regression case:\n").append(json(testCase)).append("\n\n");
        prompt.append("Normalized observation:\n").append(json(observation)).append("\n\n");
        prompt.append("Deterministic local result:\n").append(json(deterministicResult)).append("\n");
        return prompt.toString();
    }

    private Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(extractJsonObject(raw), Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String json(Object value) {
        if (value == null) {
            return "{}";
        }
        return ModelProtocolJson.compact(value);
    }

    private double decimal(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private double bounded(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
