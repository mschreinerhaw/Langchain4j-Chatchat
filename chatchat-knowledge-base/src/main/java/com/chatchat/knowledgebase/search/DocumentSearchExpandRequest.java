package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record DocumentSearchExpandRequest(
    String query,
    String docId,
    @JsonAlias({"selected_file_ids"})
    List<String> selectedFileIds,
    @JsonAlias({"selected_document_ids", "allowedDocIds", "allowed_doc_ids", "allowedDocumentIds", "allowed_document_ids"})
    List<String> selectedDocumentIds,
    @JsonAlias({"document_visibility_enforced", "strict_document_scope", "strictDocumentScope"})
    Boolean documentVisibilityEnforced,
    List<String> sections,
    Integer topK,
    Integer maxSections,
    Integer maxChunks,
    Integer maxTotalChars,
    String tenantId,
    String userId,
    List<String> roles,
    Boolean debug
) {
    public DocumentSearchExpandRequest(String query,
                                       String docId,
                                       List<String> sections,
                                       Integer topK,
                                       Integer maxSections,
                                       Integer maxChunks,
                                       Integer maxTotalChars,
                                       String tenantId,
                                       String userId,
                                       List<String> roles,
                                       Boolean debug) {
        this(query, docId, null, null, null, sections, topK, maxSections, maxChunks, maxTotalChars, tenantId, userId, roles, debug);
    }
}
