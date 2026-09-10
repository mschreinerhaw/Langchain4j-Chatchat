package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeContext;
import com.chatchat.common.knowledge.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.common.knowledge.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles only task-relevant IR and applies a conservative hard context budget. */
@Component
public class BudgetedKnowledgeContextCompiler implements KnowledgeContextCompilerPort {

    private final TokenEstimator tokenEstimator;

    public BudgetedKnowledgeContextCompiler() {
        this(new CjkAwareTokenEstimator());
    }

    @Autowired
    public BudgetedKnowledgeContextCompiler(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public KnowledgeContext compile(KnowledgeRequest request,
                                    KnowledgeSkillPlan plan,
                                    List<KnowledgeIR> units) {
        Map<String, KnowledgeIR> unique = new LinkedHashMap<>();
        if (units != null) {
            units.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(this::weightedRelevance).reversed())
                .forEach(unit -> unique.putIfAbsent(dedupKey(unit), unit));
        }
        List<KnowledgeIR> selected = new ArrayList<>();
        StringBuilder compiled = new StringBuilder();
        boolean truncated = false;
        for (KnowledgeIR unit : unique.values()) {
            String block = render(unit);
            int remaining = request.maxTokens() - tokenEstimator.estimate(compiled.toString());
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (tokenEstimator.estimate(block) > remaining) {
                block = truncateToTokenBudget(block, remaining);
                truncated = true;
            }
            if (!block.isBlank()) {
                if (!compiled.isEmpty()) compiled.append('\n');
                compiled.append(block);
                selected.add(unit);
            }
            if (truncated) break;
        }
        List<com.chatchat.common.knowledge.KnowledgeSourceReference> sources = selected.stream()
            .map(KnowledgeIR::source).filter(java.util.Objects::nonNull).distinct().toList();
        int used = tokenEstimator.estimate(compiled.toString());
        return new KnowledgeContext(KnowledgeContext.SCHEMA_VERSION, plan, selected, compiled.toString(),
            sources, used, request.maxTokens(), truncated, compiled.isEmpty() ? "empty" : "used");
    }

    int conservativeTokenEstimate(String value) {
        return tokenEstimator.estimate(value);
    }

    private String render(KnowledgeIR unit) {
        String content = !unit.compactPromptRepresentation().isBlank()
            ? unit.compactPromptRepresentation() : unit.semanticDescription();
        if (content.isBlank()) return "";
        String title = unit.title().isBlank() ? unit.knowledgeId() : unit.title();
        return "[" + unit.type() + "] " + title + "\n" + content.trim() + "\n";
    }

    private String dedupKey(KnowledgeIR unit) {
        return unit.type() + "|" + unit.compactPromptRepresentation().strip().toLowerCase();
    }

    private double weightedRelevance(KnowledgeIR unit) {
        return typeWeight(unit.type()) * unit.relevance();
    }

    private double typeWeight(KnowledgeType type) {
        return switch (type) {
            case RULE, CONSTRAINT, METRIC -> 1.0D;
            case POLICY, PROCEDURE -> 0.9D;
            case CONCEPT, METHOD -> 0.8D;
            case FAQ -> 0.7D;
            case EXAMPLE, INTERPRETATION -> 0.5D;
        };
    }

    private String truncateToTokenBudget(String value, int maxTokens) {
        if (maxTokens <= 0 || value.isEmpty()) return "";
        if (tokenEstimator.estimate(value) <= maxTokens) return value;
        String suffix = maxTokens > tokenEstimator.estimate("…") ? "…" : "";
        int contentBudget = Math.max(0, maxTokens - tokenEstimator.estimate(suffix));
        int codePointCount = value.codePointCount(0, value.length());
        int low = 0;
        int high = codePointCount;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = value.offsetByCodePoints(0, middle);
            if (tokenEstimator.estimate(value.substring(0, end)) <= contentBudget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, value.offsetByCodePoints(0, low)) + suffix;
    }
}
