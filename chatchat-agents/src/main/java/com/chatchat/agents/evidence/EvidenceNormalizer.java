package com.chatchat.agents.evidence;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EvidenceNormalizer {

    private static final int DEFAULT_LIMIT = 12;
    private static final int FINANCIAL_ROW_LIMIT = 20;
    private static final int FINANCIAL_ROW_CHAR_LIMIT = 1_500;
    private static final Set<String> FINANCIAL_INTERNAL_FIELDS = Set.of(
        "id", "collected_date", "collected_at", "source_id", "source_code", "record_key",
        "payload_json", "_omitted_fields", "_storage_tier", "snapshot_mode", "legal_risk"
    );

    private final EvidenceCitationBinder citationBinder;

    public EvidenceNormalizer() {
        this(new EvidenceCitationBinder());
    }

    public EvidenceNormalizer(EvidenceCitationBinder citationBinder) {
        this.citationBinder = citationBinder == null ? new EvidenceCitationBinder() : citationBinder;
    }

    public List<EvidenceChunk> normalize(String toolName, Object data) {
        return normalize(toolName, data, DEFAULT_LIMIT);
    }

    public List<EvidenceChunk> normalize(String toolName, Object data, int limit) {
        Map<String, Object> root = asMap(data);
        if (root.isEmpty()) {
            return List.of();
        }
        EvidenceType type = evidenceType(toolName, root);
        if (type == null) {
            return List.of();
        }
        List<Map<String, Object>> candidates = candidates(root, type);
        if (candidates.isEmpty() && root.containsKey("content")) {
            candidates.add(root);
        }

        int max = Math.max(1, limit);
        List<EvidenceChunk> chunks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : candidates) {
            if (chunks.size() >= max) {
                break;
            }
            EvidenceChunk chunk = type == EvidenceType.DOCUMENT
                ? documentChunk(root, item)
                : webChunk(root, item);
            if (chunk == null || !hasText(chunk.content())) {
                continue;
            }
            String key = dedupeKey(chunk);
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> citation = citationBinder.bind(chunk, chunks.size() + 1);
            chunks.add(new EvidenceChunk(
                chunk.evidenceType(),
                EvidenceChunk.CONTRACT_VERSION,
                chunk.source(),
                chunk.content(),
                chunk.score(),
                citation,
                chunk.governance(),
                chunk.trace()
            ));
        }
        return List.copyOf(chunks);
    }

    public List<EvidenceAudit> audits(String toolName, Object data, List<EvidenceChunk> chunks) {
        Map<String, Object> root = asMap(data);
        String query = firstNonBlank(stringValue(root.get("query")), stringValue(root.get("keyword")));
        List<EvidenceAudit> audits = new ArrayList<>();
        for (EvidenceChunk chunk : chunks == null ? List.<EvidenceChunk>of() : chunks) {
            EvidenceGovernance governance = chunk.governance();
            audits.add(new EvidenceAudit(
                query,
                toolName,
                chunk.evidenceType(),
                governance == null ? null : governance.tenantId(),
                governance == null ? null : governance.userId(),
                governance == null ? "ALLOWED" : governance.policyStatus(),
                false,
                stringValue(chunk.citation().get("refId"))
            ));
        }
        return List.copyOf(audits);
    }

    private EvidenceChunk documentChunk(Map<String, Object> root, Map<String, Object> item) {
        Map<String, Object> citation = citationMap(item);
        String fileId = firstNonBlank(
            firstNonBlank(stringValue(item.get("fileId")), stringValue(item.get("docId"))),
            firstNonBlank(stringValue(item.get("documentId")), stringValue(citation.get("fileId")))
        );
        String section = firstNonBlank(stringValue(item.get("section")), stringValue(citation.get("section")));
        Object chunkIndex = firstObject(
            item.get("chunkIndex"),
            item.get("chunk_index"),
            citation.get("chunkIndex"),
            citation.get("chunk_index")
        );
        if (chunkIndex != null) {
            citation.putIfAbsent("chunkIndex", chunkIndex);
        }
        String refId = firstNonBlank(
            stringValue(item.get("refId")),
            firstNonBlank(stringValue(citation.get("refId")), hasText(fileId) && chunkIndex != null ? "doc://" + fileId + "#chunk=" + chunkIndex : null)
        );
        if (hasText(refId)) {
            citation.put("refId", refId);
        }
        Object evidenceGrade = firstObject(item.get("evidenceGrade"), item.get("grade"), citation.get("evidenceGrade"));
        if (evidenceGrade != null) {
            citation.put("evidenceGrade", String.valueOf(evidenceGrade));
        }
        EvidenceSource source = new EvidenceSource(
            firstNonBlank(
                firstNonBlank(stringValue(item.get("fileName")), stringValue(item.get("title"))),
                firstNonBlank(stringValue(citation.get("source")), stringValue(item.get("source")))
            ),
            null,
            null,
            fileId,
            section
        );
        return new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            source,
            firstNonBlank(
                firstNonBlank(stringValue(item.get("content")), stringValue(item.get("text"))),
                firstNonBlank(
                    stringValue(item.get("contentExcerpt")),
                    firstNonBlank(stringValue(item.get("excerpt")), firstNonBlank(stringValue(item.get("snippet")), stringValue(item.get("summary"))))
                )
            ),
            doubleValue(firstObject(item.get("score"), item.get("relevanceScore"))),
            citation,
            governance(root, item),
            traceMap(item)
        );
    }

    private EvidenceChunk webChunk(Map<String, Object> root, Map<String, Object> item) {
        Map<String, Object> citation = citationMap(item);
        String financialContent = financialObservationContent(item);
        String url = firstNonBlank(
            firstNonBlank(stringValue(item.get("url")), stringValue(item.get("source_url"))),
            firstNonBlank(
                firstNonBlank(stringValue(citation.get("url")), stringValue(item.get("link"))),
                financialSourceUrl(item)
            )
        );
        String domain = firstNonBlank(
            firstNonBlank(stringValue(item.get("domain")), stringValue(citation.get("domain"))),
            domainOf(url)
        );
        EvidenceSource source = new EvidenceSource(
            firstNonBlank(
                firstNonBlank(stringValue(item.get("title")), stringValue(item.get("name"))),
                firstNonBlank(stringValue(item.get("source")), domain)
            ),
            url,
            domain,
            null,
            stringValue(item.get("section"))
        );
        return new EvidenceChunk(
            EvidenceType.WEB,
            EvidenceChunk.CONTRACT_VERSION,
            source,
            firstNonBlank(
                financialContent,
                firstNonBlank(
                    firstNonBlank(stringValue(item.get("content")), stringValue(item.get("excerpt"))),
                    firstNonBlank(
                        stringValue(item.get("pageExcerpt")),
                        firstNonBlank(stringValue(item.get("snippet")), stringValue(item.get("summary")))
                    )
                )
            ),
            doubleValue(firstObject(
                item.get("score"),
                item.get("rerank_score"),
                item.get("confidence"),
                item.get("trust_score")
            )),
            citation,
            governance(root, item),
            traceMap(item)
        );
    }

    private String financialObservationContent(Map<String, Object> item) {
        if (!"financial_data".equalsIgnoreCase(stringValue(item.get("resultType")))) {
            return null;
        }
        Object value = item.get("rows");
        if (!(value instanceof Collection<?> rows) || rows.isEmpty()) {
            return null;
        }
        String dataset = firstNonBlank(stringValue(item.get("dataset")), "unknown");
        StringBuilder content = new StringBuilder("Actual governed financial observations: dataset=")
            .append(dataset)
            .append(", returnedRows=")
            .append(rows.size())
            .append(". Values below are factual query results, not asset metadata.");
        int index = 0;
        for (Object rowValue : rows) {
            if (index >= FINANCIAL_ROW_LIMIT) break;
            Map<String, Object> row = asMap(rowValue);
            if (row.isEmpty()) continue;
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                Object fieldValue = entry.getValue();
                if (key == null || FINANCIAL_INTERNAL_FIELDS.contains(key) || emptyValue(fieldValue)) continue;
                String fragment = key + "=" + String.valueOf(fieldValue).replaceAll("\\s+", " ").trim();
                if (line.length() > 0) fragment = ", " + fragment;
                if (line.length() + fragment.length() > FINANCIAL_ROW_CHAR_LIMIT) break;
                line.append(fragment);
            }
            if (line.length() == 0) continue;
            content.append("\n[row ").append(++index).append("] ").append(line);
        }
        return index == 0 ? null : content.toString();
    }

    private String financialSourceUrl(Map<String, Object> item) {
        Object value = item.get("rows");
        if (!(value instanceof Collection<?> rows)) return null;
        for (Object rowValue : rows) {
            String url = stringValue(asMap(rowValue).get("source_url"));
            if (hasText(url)) return url;
        }
        return null;
    }

    private boolean emptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return false;
    }

    private EvidenceType evidenceType(String toolName, Map<String, Object> root) {
        String contractVersion = stringValue(root.get("contractVersion"));
        if ("document_evidence_v1".equals(contractVersion)) {
            return EvidenceType.DOCUMENT;
        }
        if ("web_evidence_v1".equals(contractVersion) || root.containsKey("web_search_mode")) {
            return EvidenceType.WEB;
        }
        String semantic = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (semantic.startsWith("mcp_")) {
            semantic = semantic.substring(4);
        }
        if (semantic.contains("document") && semantic.contains("search")) {
            return EvidenceType.DOCUMENT;
        }
        if (semantic.contains("web") || semantic.contains("crawl") || semantic.contains("search_and_extract")
            || semantic.contains("retrieve_financial_evidence")) {
            return EvidenceType.WEB;
        }
        return null;
    }

    private List<Map<String, Object>> candidates(Map<String, Object> root, EvidenceType type) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (type == EvidenceType.DOCUMENT) {
            addCandidateList(values, root.get("results"));
            addCandidateList(values, root.get("evidenceChunks"));
            addCandidateList(values, root.get("evidence_chunks"));
            addCandidateList(values, root.get("evidenceSnippets"));
            addCandidateList(values, root.get("items"));
            addCandidateList(values, root.get("records"));
        } else {
            addCandidateList(values, root.get("evidence_chunks"));
            addCandidateList(values, root.get("evidenceChunks"));
            addCandidateList(values, root.get("results"));
            addCandidateList(values, root.get("pageExcerpts"));
            addCandidateList(values, root.get("evidenceSnippets"));
            addCandidateList(values, root.get("items"));
            addCandidateList(values, root.get("organic_results"));
        }
        return values;
    }

    private EvidenceGovernance governance(Map<String, Object> root, Map<String, Object> item) {
        Map<String, Object> requestContext = asMap(root.get("requestContext"));
        String tenantId = firstNonBlank(
            firstNonBlank(stringValue(item.get("tenantId")), stringValue(requestContext.get("tenantId"))),
            "default"
        );
        String userId = firstNonBlank(
            firstNonBlank(stringValue(item.get("userId")), stringValue(requestContext.get("userId"))),
            "anonymous"
        );
        List<String> roles = stringList(firstObject(item.get("roles"), item.get("permissionRoles"), requestContext.get("roles")));
        String policyStatus = Boolean.TRUE.equals(root.get("blocked")) || Boolean.TRUE.equals(item.get("blocked"))
            ? "BLOCKED"
            : "ALLOWED";
        return new EvidenceGovernance(tenantId, userId, roles, policyStatus);
    }

    private Map<String, Object> citationMap(Map<String, Object> item) {
        Map<String, Object> citation = asMap(item.get("citation"));
        Object citations = item.get("citations");
        if (citation.isEmpty() && citations instanceof List<?> list && !list.isEmpty()) {
            citation = asMap(list.get(0));
        }
        return new LinkedHashMap<>(citation);
    }

    private Map<String, Object> traceMap(Map<String, Object> item) {
        Map<String, Object> trace = new LinkedHashMap<>(asMap(item.get("trace")));
        if (item.containsKey("source")) {
            trace.putIfAbsent("source", item.get("source"));
        }
        if (item.containsKey("rank")) {
            trace.putIfAbsent("rank", item.get("rank"));
        }
        if (item.containsKey("contentMode")) {
            trace.putIfAbsent("contentMode", item.get("contentMode"));
        }
        return trace;
    }

    private void addCandidateList(List<Map<String, Object>> candidates, Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return;
        }
        for (Object item : collection) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                candidates.add(map);
            }
        }
    }

    private String dedupeKey(EvidenceChunk chunk) {
        String refId = stringValue(chunk.citation().get("refId"));
        if (hasText(refId)) {
            return refId;
        }
        EvidenceSource source = chunk.source();
        return (source == null ? "" : firstNonBlank(source.url(), source.fileId()))
            + "|"
            + shortText(chunk.content(), 80);
    }

    private String domainOf(String url) {
        if (!hasText(url)) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addString(values, item);
            }
        } else if (value instanceof String text) {
            for (String part : text.split("[,;\\s]+")) {
                addString(values, part);
            }
        } else {
            addString(values, value);
        }
        return values;
    }

    private void addString(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank() && !values.contains(text)) {
            values.add(text);
        }
    }

    private Object firstObject(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String shortText(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, Math.max(0, maxChars));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (hasText(first)) {
            return first.trim();
        }
        return hasText(second) ? second.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
