package com.chatchat.knowledgebase.search;

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
    Boolean debug
) {
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
