package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;


import dev.langchain4j.model.chat.ChatModel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Accounts every model invocation and enforces configured token/cost ceilings. */
public final class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ContextTokenEstimator estimator = new ContextTokenEstimator();
    private final Map<String, Object> usage;
    private final long tokenBudget;
    private final double costBudget;
    private final double inputCostPerThousandTokens;
    private final double outputCostPerThousandTokens;
    private final double alertRatio;
    private long inputTokens;
    private long outputTokens;
    private long invocations;
    private long latencyMs;
    private double estimatedCost;

    public MeteredChatModel(ChatModel delegate,
                            Map<String, Object> usage,
                            long tokenBudget,
                            double costBudget,
                            double inputCostPerThousandTokens,
                            double outputCostPerThousandTokens,
                            double alertRatio) {
        this.delegate = delegate;
        this.usage = usage == null ? new LinkedHashMap<>() : usage;
        this.tokenBudget = Math.max(0, tokenBudget);
        this.costBudget = Math.max(0D, costBudget);
        this.inputCostPerThousandTokens = Math.max(0D, inputCostPerThousandTokens);
        this.outputCostPerThousandTokens = Math.max(0D, outputCostPerThousandTokens);
        this.alertRatio = Math.max(0.01D, Math.min(1D, alertRatio));
        publish();
    }

    @Override
    public String chat(String message) {
        long promptTokens = estimator.estimate(message).tokens();
        synchronized (this) {
            enforce(inputTokens + outputTokens + promptTokens,
                estimatedCost + promptTokens * inputCostPerThousandTokens / 1_000D);
        }
        long startedAt = System.currentTimeMillis();
        String response = delegate.chat(message);
        long responseTokens = estimator.estimate(response).tokens();
        synchronized (this) {
            inputTokens += promptTokens;
            outputTokens += responseTokens;
            invocations++;
            latencyMs += Math.max(0, System.currentTimeMillis() - startedAt);
            estimatedCost += promptTokens * inputCostPerThousandTokens / 1_000D
                + responseTokens * outputCostPerThousandTokens / 1_000D;
            publish();
            enforce(inputTokens + outputTokens, estimatedCost);
        }
        return response;
    }

    private void enforce(long tokens, double cost) {
        if (tokenBudget > 0 && tokens > tokenBudget) {
            throw new AgentBudgetExceededException(
                "AGENT_TOKEN_BUDGET_EXCEEDED: used=" + tokens + ", budget=" + tokenBudget);
        }
        if (costBudget > 0D && cost > costBudget) {
            throw new AgentBudgetExceededException(
                "AGENT_COST_BUDGET_EXCEEDED: used=" + cost + ", budget=" + costBudget);
        }
    }

    private void publish() {
        usage.put("contractVersion", "agent_model_usage_v1");
        usage.put("invocations", invocations);
        usage.put("inputTokensEstimated", inputTokens);
        usage.put("outputTokensEstimated", outputTokens);
        usage.put("totalTokensEstimated", inputTokens + outputTokens);
        usage.put("modelLatencyMs", latencyMs);
        usage.put("estimatedCost", round(estimatedCost));
        usage.put("tokenBudget", tokenBudget == 0 ? null : tokenBudget);
        usage.put("costBudget", costBudget == 0D ? null : costBudget);
        usage.put("budgetAlert", alert());
    }

    private boolean alert() {
        boolean tokenAlert = tokenBudget > 0 && inputTokens + outputTokens >= tokenBudget * alertRatio;
        boolean costAlert = costBudget > 0D && estimatedCost >= costBudget * alertRatio;
        return tokenAlert || costAlert;
    }

    private double round(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }
}
