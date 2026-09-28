package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.workflow.DocumentParentSection;

import java.util.List;
import java.util.Map;

public record DocumentRecallResult(
    SearchPage documentPage,
    SearchPage chunkPage,
    List<DocumentSearchCandidate> candidates,
    List<String> irDocumentIds,
    String focusedQuery,
    Map<String, SearchDocument> verifiedSources,
    Map<String, List<DocumentParentSection>> parentSections
) {

    public DocumentRecallResult(SearchPage documentPage, SearchPage chunkPage,
                                List<DocumentSearchCandidate> candidates, List<String> irDocumentIds,
                                String focusedQuery) {
        this(documentPage, chunkPage, candidates, irDocumentIds, focusedQuery, Map.of(), Map.of());
    }

    public DocumentRecallResult(SearchPage documentPage, SearchPage chunkPage,
                                List<DocumentSearchCandidate> candidates, List<String> irDocumentIds,
                                String focusedQuery, Map<String, SearchDocument> verifiedSources) {
        this(documentPage, chunkPage, candidates, irDocumentIds, focusedQuery, verifiedSources, Map.of());
    }

    public DocumentRecallResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        irDocumentIds = irDocumentIds == null ? List.of() : List.copyOf(irDocumentIds);
        focusedQuery = focusedQuery == null ? "" : focusedQuery;
        verifiedSources = verifiedSources == null ? Map.of() : Map.copyOf(verifiedSources);
        parentSections = parentSections == null ? Map.of() : Map.copyOf(parentSections);
    }
}
