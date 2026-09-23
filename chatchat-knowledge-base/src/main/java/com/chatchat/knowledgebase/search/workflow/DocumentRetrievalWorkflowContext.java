package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.common.retrieval.AuthorizedRetrieval;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable execution state shared by ordered retrieval stages for one request. */
public final class DocumentRetrievalWorkflowContext {
    private final DocumentSearchPlan originalPlan;
    private final int documentLimit;
    private ProblemQueryAnalysis queryAnalysis;
    private SkillRoleRetrievalContext skillRoleContext;
    private DocumentSearchPlan searchPlan;
    private List<String> authorizedDocumentIds = List.of();
    private String focusedQuery = "";
    private SearchPage documentPage;
    private SearchPage chunkPage;
    private AuthorizedRetrieval.Scope authorizationScope;
    private List<DocumentSearchCandidate> candidates = List.of();
    private final Map<String, SearchDocument> verifiedSources = new LinkedHashMap<>();
    private final Map<String, List<DocumentParentSection>> parentSections = new LinkedHashMap<>();
    private final List<String> completedStages = new ArrayList<>();
    private boolean stopped;

    public DocumentRetrievalWorkflowContext(DocumentSearchPlan plan, int documentLimit) {
        this.originalPlan = plan;
        this.searchPlan = plan;
        this.documentLimit = Math.max(1, documentLimit);
    }

    public DocumentSearchPlan originalPlan() { return originalPlan; }
    public ProblemQueryAnalysis queryAnalysis() { return queryAnalysis; }
    public void queryAnalysis(ProblemQueryAnalysis value) { queryAnalysis = value; }
    public SkillRoleRetrievalContext skillRoleContext() { return skillRoleContext; }
    public void skillRoleContext(SkillRoleRetrievalContext value) { skillRoleContext = value; }
    public int documentLimit() { return documentLimit; }
    public DocumentSearchPlan searchPlan() { return searchPlan; }
    public void searchPlan(DocumentSearchPlan value) { searchPlan = value; }
    public List<String> authorizedDocumentIds() { return authorizedDocumentIds; }
    public void authorizedDocumentIds(List<String> value) {
        authorizedDocumentIds = value == null ? List.of() : List.copyOf(value);
    }
    public String focusedQuery() { return focusedQuery; }
    public void focusedQuery(String value) { focusedQuery = value == null ? "" : value; }
    public SearchPage documentPage() { return documentPage; }
    public void documentPage(SearchPage value) { documentPage = value; }
    public SearchPage chunkPage() { return chunkPage; }
    public void chunkPage(SearchPage value) { chunkPage = value; }
    public AuthorizedRetrieval.Scope authorizationScope() { return authorizationScope; }
    public void authorizationScope(AuthorizedRetrieval.Scope value) { authorizationScope = value; }
    public List<DocumentSearchCandidate> candidates() { return candidates; }
    public void candidates(List<DocumentSearchCandidate> value) {
        candidates = value == null ? List.of() : List.copyOf(value);
    }
    public Map<String, SearchDocument> verifiedSources() { return Map.copyOf(verifiedSources); }
    public void verifiedSource(String documentId, SearchDocument source) {
        if (documentId != null && source != null) verifiedSources.put(documentId, source);
    }
    public Map<String, List<DocumentParentSection>> parentSections() { return Map.copyOf(parentSections); }
    public void parentSections(String documentId, List<DocumentParentSection> sections) {
        if (documentId != null && sections != null && !sections.isEmpty()) {
            parentSections.put(documentId, List.copyOf(sections));
        }
    }
    public List<String> completedStages() { return List.copyOf(completedStages); }
    public void completed(String stageId) { completedStages.add(stageId); }
    public boolean stopped() { return stopped; }
    public void stop() { stopped = true; }
}
