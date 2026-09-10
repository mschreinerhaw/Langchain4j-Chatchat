package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeContext;
import com.chatchat.common.knowledge.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Compiles only task-relevant IR and applies a conservative hard context budget. */
@Component
public class BudgetedKnowledgeContextCompiler implements KnowledgeContextCompilerPort {

    @Override
    public KnowledgeContext compile(KnowledgeRequest request,
                                    KnowledgeSkillPlan plan,
                                    List<KnowledgeIR> units) {
        Map<String, KnowledgeIR> unique = new LinkedHashMap<>();
        if (units != null) {
            units.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(KnowledgeIR::relevance).reversed())
                .forEach(unit -> unique.putIfAbsent(dedupKey(unit), unit));
        }
        List<KnowledgeIR> selected = new ArrayList<>();
        StringBuilder compiled = new StringBuilder();
        boolean truncated = false;
        for (KnowledgeIR unit : unique.values()) {
            String block = render(unit);
            int remaining = request.maxTokens() - conservativeTokenEstimate(compiled.toString());
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (conservativeTokenEstimate(block) > remaining) {
                block = truncateCodePoints(block, remaining);
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
        int used = conservativeTokenEstimate(compiled.toString());
        return new KnowledgeContext(KnowledgeContext.SCHEMA_VERSION, plan, selected, compiled.toString(),
            sources, used, request.maxTokens(), truncated, compiled.isEmpty() ? "empty" : "used");
    }

    int conservativeTokenEstimate(String value) {
        // Byte length is a safe upper bound for byte-level model tokenizers.
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
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

    private String truncateCodePoints(String value, int maxCodePoints) {
        if (maxCodePoints <= 0 || value.isEmpty()) return "";
        if (conservativeTokenEstimate(value) <= maxCodePoints) return value;
        String suffix = maxCodePoints >= 3 ? "…" : "";
        int byteLimit = Math.max(0, maxCodePoints - conservativeTokenEstimate(suffix));
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            if (conservativeTokenEstimate(result + next) > byteLimit) break;
            result.append(next);
            offset += Character.charCount(codePoint);
        }
        return result + suffix;
    }
}
