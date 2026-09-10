package com.chatchat.knowledgebase.runtime.extraction;

import com.chatchat.common.knowledge.KnowledgeExtractionPort;
import com.chatchat.common.knowledge.KnowledgeExtractionRequest;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeIRIndexPort;
import com.chatchat.common.knowledge.KnowledgeIndexDocument;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates ingestion-time extraction and atomic replacement of a document's Knowledge IR. */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentIngestionService {

    private final KnowledgeExtractionPort extractor;
    private final KnowledgeIRIndexPort index;

    public int extractAndIndex(SearchDocument document) {
        if (document == null || document.getDocId() == null || document.getContent() == null
            || document.getContent().isBlank()) return 0;
        String domain = first(document.getIndustries(), first(document.getTags(), "general"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "title", document.getTitle());
        putIfPresent(metadata, "fileName", document.getFileName());
        metadata.put("version", document.getVersion() == null ? "" : String.valueOf(document.getVersion()));
        KnowledgeExtractionRequest request = new KnowledgeExtractionRequest(
            document.getDocId(), domain,
            document.getFilePath() == null || document.getFilePath().isBlank()
                ? "document://" + document.getDocId() : document.getFilePath(),
            document.getContent(), null, metadata);
        List<KnowledgeIR> units = extractor.extract(request);
        index.replaceDocument(new KnowledgeIndexDocument(
            document.getDocId(), document.getTenantId(), document.getUserId(), document.getVisibility(),
            document.getPermissionRoles(), document.getTags(),
            document.getVersion() == null ? "" : String.valueOf(document.getVersion()), units));
        log.info("knowledge_document_indexed documentId={} units={} domain={}",
            document.getDocId(), units.size(), domain);
        return units.size();
    }

    public void delete(String documentId) {
        index.deleteDocument(documentId);
    }

    private String first(List<String> values, String fallback) {
        return values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()
            ? fallback : values.get(0).trim();
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) metadata.put(key, value);
    }
}
