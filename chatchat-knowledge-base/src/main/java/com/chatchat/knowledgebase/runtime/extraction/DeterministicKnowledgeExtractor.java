package com.chatchat.knowledgebase.runtime.extraction;

import com.chatchat.common.knowledge.KnowledgeExtractionPort;
import com.chatchat.common.knowledge.KnowledgeExtractionRequest;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRule;
import com.chatchat.common.knowledge.KnowledgeSourceReference;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.knowledgebase.search.query.TextChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Deterministic ingestion extractor used when no specialized domain extractor is installed. */
@Component
@RequiredArgsConstructor
public class DeterministicKnowledgeExtractor implements KnowledgeExtractionPort {

    private static final int EXTRACTION_CHUNK_CHARS = 1200;
    private static final int COMPACT_CHARS = 850;
    private final TextChunker chunker;

    @Override
    public List<KnowledgeIR> extract(KnowledgeExtractionRequest request) {
        List<TextChunker.TextChunk> chunks = chunker.splitChunks(
            request.normalizedText(), EXTRACTION_CHUNK_CHARS, 100);
        List<KnowledgeIR> units = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            TextChunker.TextChunk chunk = chunks.get(index);
            KnowledgeType type = classify(chunk.content());
            if (!request.allowedTypes().contains(type)) continue;
            String compact = compact(chunk.content());
            String title = !chunk.section().isBlank() ? chunk.section() : metadata(request, "title", "Knowledge unit " + (index + 1));
            String chunkId = request.sourceId() + "-knowledge-" + (index + 1);
            KnowledgeSourceReference source = new KnowledgeSourceReference(
                request.sourceId(), request.sourceId(), chunkId,
                metadata(request, "fileName", metadata(request, "title", request.sourceId())),
                chunk.section(), metadata(request, "version", ""),
                "doc://" + request.sourceId() + "#knowledge=" + (index + 1));
            List<String> constraints = extractConstraints(chunk.content());
            List<KnowledgeRule> rules = type == KnowledgeType.RULE
                ? List.of(new KnowledgeRule(title, compact, List.of())) : List.of();
            units.add(new KnowledgeIR(
                stableId(request.sourceId(), type, compact), request.domain(), type, title,
                compact, rules, constraints, List.of(), List.of(), compact, source, 0.65D));
        }
        return List.copyOf(units);
    }

    private KnowledgeType classify(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        if (containsAny(text, "不得", "禁止", "限制", "约束", "must not", "prohibited")) return KnowledgeType.CONSTRAINT;
        if (containsAny(text, "指标", "口径", "计算公式", "比例", "metric", "formula")) return KnowledgeType.METRIC;
        if (containsAny(text, "必须", "应当", "规则", "阈值", "判断", "rule", "threshold")) return KnowledgeType.RULE;
        if (containsAny(text, "制度", "办法", "规范", "条例", "policy", "regulation")) return KnowledgeType.POLICY;
        if (containsAny(text, "步骤", "流程", "办理", "操作", "procedure", "workflow")) return KnowledgeType.PROCEDURE;
        if (containsAny(text, "方法", "分析框架", "methodology", "approach")) return KnowledgeType.METHOD;
        if (containsAny(text, "是指", "定义", "概念", "means", "definition")) return KnowledgeType.CONCEPT;
        if (containsAny(text, "常见问题", "问答", "faq")) return KnowledgeType.FAQ;
        if (containsAny(text, "例如", "示例", "案例", "example", "case")) return KnowledgeType.EXAMPLE;
        return KnowledgeType.INTERPRETATION;
    }

    private List<String> extractConstraints(String content) {
        return List.of(content.split("[。！？.!?\\n]" )).stream()
            .map(String::trim)
            .filter(value -> containsAny(value.toLowerCase(Locale.ROOT),
                "不得", "禁止", "必须", "应当", "限制", "must", "shall", "prohibited"))
            .map(this::compact).distinct().limit(8).toList();
    }

    private String compact(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= COMPACT_CHARS ? normalized : normalized.substring(0, COMPACT_CHARS) + "…";
    }

    private String stableId(String sourceId, KnowledgeType type, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((sourceId + "|" + type + "|" + content).getBytes(StandardCharsets.UTF_8));
            return "kir-" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create Knowledge IR id", ex);
        }
    }

    private String metadata(KnowledgeExtractionRequest request, String key, String fallback) {
        Object value = request.metadata().get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
