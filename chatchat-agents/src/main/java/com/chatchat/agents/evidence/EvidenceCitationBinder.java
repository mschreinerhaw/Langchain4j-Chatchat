package com.chatchat.agents.evidence;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public class EvidenceCitationBinder {

    public Map<String, Object> bind(EvidenceChunk chunk, int index) {
        if (chunk == null) {
            return Map.of();
        }
        Map<String, Object> citation = new LinkedHashMap<>(chunk.citation());
        citation.putIfAbsent("refId", refId(chunk, index));
        citation.putIfAbsent("type", chunk.evidenceType() == null ? null : chunk.evidenceType().name());
        if (chunk.source() != null) {
            citation.putIfAbsent("source", chunk.source().name());
            citation.putIfAbsent("url", chunk.source().url());
            citation.putIfAbsent("domain", chunk.source().domain());
            citation.putIfAbsent("fileId", chunk.source().fileId());
            citation.putIfAbsent("section", chunk.source().section());
        }
        return citation;
    }

    public String refId(EvidenceChunk chunk, int index) {
        if (chunk == null) {
            return "";
        }
        Object existing = chunk.citation() == null ? null : chunk.citation().get("refId");
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return String.valueOf(existing).trim();
        }
        if (chunk.evidenceType() == EvidenceType.DOCUMENT) {
            return documentRefId(chunk, index);
        }
        return webRefId(chunk, index);
    }

    private String documentRefId(EvidenceChunk chunk, int index) {
        EvidenceSource source = chunk.source();
        String fileId = source == null ? null : source.fileId();
        Object chunkIndex = chunk.citation() == null ? null : chunk.citation().get("chunkIndex");
        if (chunkIndex == null && chunk.citation() != null) {
            chunkIndex = chunk.citation().get("chunk_index");
        }
        if (hasText(fileId) && chunkIndex != null && hasText(String.valueOf(chunkIndex))) {
            return "doc://" + fileId + "#chunk=" + chunkIndex;
        }
        return "doc://unknown#chunk=" + Math.max(1, index);
    }

    private String webRefId(EvidenceChunk chunk, int index) {
        EvidenceSource source = chunk.source();
        String url = source == null ? null : source.url();
        String domain = source == null ? null : source.domain();
        String path = "";
        if (hasText(url)) {
            try {
                URI uri = URI.create(url);
                domain = hasText(domain) ? domain : uri.getHost();
                path = uri.getRawPath();
            } catch (Exception ignored) {
                path = "";
            }
        }
        if (!hasText(domain)) {
            domain = "unknown";
        }
        if (!hasText(path)) {
            path = "/";
        }
        return "web://" + domain + path + "#result=" + Math.max(1, index);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
