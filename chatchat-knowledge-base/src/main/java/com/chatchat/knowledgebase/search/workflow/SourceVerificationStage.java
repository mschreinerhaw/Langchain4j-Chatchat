package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.index.PerDocumentIndexService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.model.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.model.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rechecks current RocksDB source, version, ACL, and indexed passage text before return assembly. */
@Component
@RequiredArgsConstructor
public class SourceVerificationStage implements DocumentRetrievalStage {
    private final PerDocumentIndexService documents;

    @Override public String id() { return "source-verification"; }
    @Override public int order() { return 600; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        List<DocumentSearchCandidate> verified = new ArrayList<>();
        for (DocumentSearchCandidate candidate : context.candidates()) {
            SearchResult result = candidate == null ? null : candidate.result();
            if (result == null || result.docId() == null) continue;
            SearchDocument source = documents.openDocumentIndex(result.docId(),
                context.originalPlan().permissionContext()).orElse(null);
            if (source == null || !sameVersion(source, result) || !containsAnyPassage(source, result.matchedChunks())) {
                continue;
            }
            context.verifiedSource(result.docId(), source);
            verified.add(candidate);
        }
        context.candidates(verified);
    }

    private boolean sameVersion(SearchDocument source, SearchResult result) {
        return source.getVersion() == null || result.version() <= 0 || source.getVersion().equals(result.version());
    }

    private boolean containsAnyPassage(SearchDocument source, List<SearchMatchedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return true;
        String body = normalize(source.getContent());
        return !body.isEmpty() && chunks.stream().map(this::passage).map(this::normalize)
            .filter(text -> !text.isEmpty()).anyMatch(body::contains);
    }

    private String passage(SearchMatchedChunk chunk) {
        if (chunk == null) return "";
        return chunk.content() == null || chunk.content().isBlank() ? chunk.text() : chunk.content();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
