package com.chatchat.knowledgebase.search.document.api.search;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record DocumentSearchRequest(
    String query,
    Integer topK,
    @JsonAlias({"document_ids", "documentIds", "file_ids"})
    List<String> fileIds,
    @JsonAlias({"selected_file_ids"})
    List<String> selectedFileIds,
    @JsonAlias({"selected_document_ids", "allowedDocIds", "allowed_doc_ids", "allowedDocumentIds", "allowed_document_ids"})
    List<String> selectedDocumentIds,
    @JsonAlias({"document_visibility_enforced", "strict_document_scope", "strictDocumentScope"})
    Boolean documentVisibilityEnforced,
    DocumentSearchFilters filters,
    String tenantId,
    String userId,
    List<String> roles,
    Boolean debug,
    String agentId
) {
    public DocumentSearchRequest(String query, Integer topK, List<String> fileIds, List<String> selectedFileIds,
                                 List<String> selectedDocumentIds, Boolean documentVisibilityEnforced,
                                 DocumentSearchFilters filters, String tenantId, String userId, List<String> roles, Boolean debug) {
        this(query, topK, fileIds, selectedFileIds, selectedDocumentIds, documentVisibilityEnforced,
            filters, tenantId, userId, roles, debug, null);
    }

    public DocumentSearchRequest withAgentId(String id) {
        return new DocumentSearchRequest(query, topK, fileIds, selectedFileIds, selectedDocumentIds,
            documentVisibilityEnforced, filters, tenantId, userId, roles, debug, id);
    }
    public DocumentSearchRequest(String query,
                                 Integer topK,
                                 List<String> fileIds,
                                 DocumentSearchFilters filters,
                                 String tenantId,
                                 String userId,
                                 List<String> roles,
                                 Boolean debug) {
        this(query, topK, fileIds, null, null, null, filters, tenantId, userId, roles, debug);
    }
}
