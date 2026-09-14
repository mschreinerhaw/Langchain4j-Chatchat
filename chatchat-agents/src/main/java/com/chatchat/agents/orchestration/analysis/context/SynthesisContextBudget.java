package com.chatchat.agents.orchestration.analysis.context;

import com.chatchat.agents.orchestration.planning.model.AgentContextBudget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime allocation of a model context window for analysis synthesis.
 *
 * <p>This is an infrastructure policy: it contains no dataset, metric or domain rules. The
 * allocation travels in run metadata so every analysis path uses the capability of the active
 * model instead of unrelated local size constants.</p>
 */
public record SynthesisContextBudget(
    int contextWindowTokens,
    int reservedSystemTokens,
    int reservedHistoryTokens,
    int reservedOutputTokens,
    int inputTokens,
    int pipelineTokens,
    int claimLedgerTokens,
    int datasetTokens,
    int supplementaryTokens
) {
    public static final String RUNTIME_KEY = "analysisSynthesisContextBudget";

    public static SynthesisContextBudget from(AgentContextBudget source) {
        int input = Math.max(1_000, source.maxTokens() - source.reservedSystemTokens()
            - source.reservedHistoryTokens() - source.reservedOutputTokens());
        return new SynthesisContextBudget(source.maxTokens(), source.reservedSystemTokens(),
            source.reservedHistoryTokens(), source.reservedOutputTokens(), input,
            share(input, 25), share(input, 30), share(input, 20), share(input, 15));
    }

    public static SynthesisContextBudget fromRuntime(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get(RUNTIME_KEY);
        if (raw instanceof SynthesisContextBudget budget) return budget;
        if (raw instanceof Map<?, ?> values) {
            int window = integer(values.get("contextWindowTokens"), 32_000);
            int system = integer(values.get("reservedSystemTokens"), 0);
            int history = integer(values.get("reservedHistoryTokens"), 0);
            int output = integer(values.get("reservedOutputTokens"), 4_000);
            int input = Math.max(1_000, integer(values.get("inputTokens"),
                window - system - history - output));
            return new SynthesisContextBudget(window, system, history, output, input,
                integer(values.get("pipelineTokens"), share(input, 25)),
                integer(values.get("claimLedgerTokens"), share(input, 30)),
                integer(values.get("datasetTokens"), share(input, 20)),
                integer(values.get("supplementaryTokens"), share(input, 15)));
        }
        // Compatibility envelope for embedders that have not supplied runtime capabilities.
        // Production always installs the active model budget in run metadata.
        return from(new AgentContextBudget(24_000, 0, 0, 4_000));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contextWindowTokens", contextWindowTokens);
        result.put("reservedSystemTokens", reservedSystemTokens);
        result.put("reservedHistoryTokens", reservedHistoryTokens);
        result.put("reservedOutputTokens", reservedOutputTokens);
        result.put("inputTokens", inputTokens);
        result.put("pipelineTokens", pipelineTokens);
        result.put("claimLedgerTokens", claimLedgerTokens);
        result.put("datasetTokens", datasetTokens);
        result.put("supplementaryTokens", supplementaryTokens);
        return Map.copyOf(result);
    }

    private static int share(int input, int percent) {
        return Math.max(256, Math.multiplyExact(input, percent) / 100);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return value == null ? fallback : Math.max(0, Integer.parseInt(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
