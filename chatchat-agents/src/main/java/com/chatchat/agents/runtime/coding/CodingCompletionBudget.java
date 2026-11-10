package com.chatchat.agents.runtime.coding;

import java.util.Map;

/** Mechanical resource allocation only. No semantic planning or extra model call. */
public record CodingCompletionBudget(double minimumRatio, double maximumRatio) {
    public CodingCompletionBudget {
        if (!Double.isFinite(minimumRatio) || !Double.isFinite(maximumRatio)
                || minimumRatio <= 0 || maximumRatio < minimumRatio || maximumRatio >= 1)
            throw new IllegalArgumentException("Completion reservation ratios must satisfy 0 < min <= max < 1");
    }

    public Map<String,Object> allocate(long totalTokens, long totalCalls, long totalMillis,
            long remainingTokens, long remainingCalls, long remainingMillis, long contextTokens,
            int requiredArtifacts, int failedExecutions, boolean hasDeliverable) {
        // Larger output/repair obligations need more room; never add resources to the contract.
        double pressure = Math.min(1.0, Math.log1p(Math.max(0, requiredArtifacts) + Math.max(0, failedExecutions)) / 4.0);
        double ratio = minimumRatio + (maximumRatio - minimumRatio) * pressure;
        boolean unlimitedTokens=com.chatchat.common.runtime.coding.CodingLimit.unlimited(totalTokens);
        boolean unlimitedCalls=com.chatchat.common.runtime.coding.CodingLimit.unlimited(totalCalls);
        long tokens = unlimitedTokens?0:Math.min(totalTokens, Math.max((long)Math.ceil(totalTokens * ratio), contextTokens));
        long calls = unlimitedCalls?0:Math.min(totalCalls, (long)Math.ceil(totalCalls * ratio));
        long millis = Math.min(totalMillis, (long)Math.ceil(totalMillis * ratio));
        boolean completion = hasDeliverable || (!unlimitedTokens&&remainingTokens <= tokens) || (!unlimitedCalls&&remainingCalls <= calls) || remainingMillis <= millis;
        return Map.of("phase", completion ? "COMPLETION" : "EXPLORATION", "ratio", ratio,
            "reservedTokens", tokens, "reservedCalls", calls, "reservedMillis", millis,
            "explorationTokens", unlimitedTokens?"unlimited":Math.max(0, remainingTokens - tokens),
            "explorationCalls", unlimitedCalls?"unlimited":Math.max(0, remainingCalls - calls),
            "explorationMillis", Math.max(0, remainingMillis - millis),
            "allocationOnly", true);
    }
}
