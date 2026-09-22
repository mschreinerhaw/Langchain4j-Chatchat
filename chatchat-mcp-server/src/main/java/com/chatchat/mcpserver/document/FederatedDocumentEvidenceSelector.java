package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchHit;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Chooses the corpus with stronger evidence for the query's original leading subject. */
@Component
@RequiredArgsConstructor
public class FederatedDocumentEvidenceSelector {
    private final SearchTokenizer tokenizer;
    private final EvidenceContextFormatter formatter;

    public DocumentSearchResult select(String query, int topK, DocumentSearchResult local, DocumentSearchResult api) {
        if (api == null) return local;
        if (local == null || local.total() == 0) return api;
        if (api.total() == 0) return local;
        List<String> terms = tokenizer.searchTokens(query);
        if (terms.isEmpty()) return local;
        String subject = terms.get(0);
        int localScore = score(local, terms, subject);
        int apiScore = score(api, terms, subject);
        DocumentSearchResult primary = apiScore > localScore ? api : local;
        DocumentSearchResult secondary = primary == api ? local : api;
        // A source that never mentions the queried subject is not supporting evidence.
        if (Math.max(apiScore, localScore) >= 100 && Math.min(apiScore, localScore) < 100) {
            return primary;
        }
        Map<String, DocumentEvidenceChunk> chunks = new LinkedHashMap<>();
        int primaryLimit = secondary.results().isEmpty() || topK <= 1
            ? topK : Math.max(1, topK - 2);
        addChunks(chunks, primary.results(), primaryLimit);
        addChunks(chunks, secondary.results(), topK);
        addChunks(chunks, primary.results(), topK);
        Map<String, DocumentSearchHit> documents = new LinkedHashMap<>();
        addDocuments(documents, primary.documents());
        addDocuments(documents, secondary.documents());
        if (chunks.size() == primary.results().size() && documents.size() == primary.documents().size()) {
            return primary;
        }
        List<DocumentEvidenceChunk> evidence = new ArrayList<>(chunks.values());
        return new DocumentSearchResult(
            primary.contractVersion(), query, primary.intent(), evidence.size() + documents.size(),
            evidence, formatter.formatContext(evidence), formatter.citations(evidence),
            primary.retrievalState(), primary.evidenceQuality(), primary.retrievalEvents(),
            null, null, new ArrayList<>(documents.values()), primary.outline(),
            primary.outlineSource(), primary.expansionPolicy(), primary.evidenceGovernancePolicy(),
            primary.reasoning(), primary.decision());
    }

    private void addChunks(Map<String, DocumentEvidenceChunk> target,
                           List<DocumentEvidenceChunk> source, int topK) {
        for (DocumentEvidenceChunk chunk : source) {
            if (target.size() >= Math.max(1, topK)) break;
            target.putIfAbsent(chunk.fileId() + ":" + chunk.chunkId(), chunk);
        }
    }

    private void addDocuments(Map<String, DocumentSearchHit> target, List<DocumentSearchHit> source) {
        for (DocumentSearchHit document : source) target.putIfAbsent(document.docId(), document);
    }

    private int score(DocumentSearchResult result, List<String> terms, String subject) {
        StringBuilder text = new StringBuilder();
        for (DocumentEvidenceChunk chunk : result.results()) {
            append(text, chunk.fileName());
            append(text, chunk.section());
            append(text, chunk.content());
        }
        for (DocumentSearchHit document : result.documents()) {
            append(text, document.title());
            append(text, document.fileName());
        }
        String evidence = text.toString().toLowerCase(Locale.ROOT);
        int score = evidence.contains(subject) ? 100 : 0;
        for (String term : terms) {
            if (evidence.contains(term)) score++;
        }
        return score;
    }

    private void append(StringBuilder target, String value) {
        if (value != null) target.append(' ').append(value);
    }
}
