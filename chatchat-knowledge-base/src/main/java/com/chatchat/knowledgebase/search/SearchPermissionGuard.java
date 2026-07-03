package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SearchPermissionGuard {

    public SearchPermissionContext permissionContext(DocumentSearchRequest request) {
        return request == null
            ? SearchPermissionContext.system()
            : SearchPermissionContext.of(request.tenantId(), request.userId(), request.roles());
    }

    public SearchPermissionContext permissionContext(DocumentSearchExpandRequest request) {
        return request == null
            ? SearchPermissionContext.system()
            : SearchPermissionContext.of(request.tenantId(), request.userId(), request.roles());
    }

    public DocumentVisibilityContext visibilityContext(DocumentSearchRequest request, SearchPermissionContext permissionContext) {
        if (request == null) {
            return DocumentVisibilityContext.unrestricted();
        }
        if (permissionContext != null && permissionContext.isSuperAdmin() && visibilityRequested(request)) {
            return DocumentVisibilityContext.unrestricted();
        }
        List<String> selected = !safeList(request.selectedDocumentIds()).isEmpty()
            ? safeList(request.selectedDocumentIds())
            : safeList(request.selectedFileIds());
        return DocumentVisibilityContext.of(selected, request.documentVisibilityEnforced());
    }

    public DocumentVisibilityContext visibilityContext(DocumentSearchExpandRequest request, SearchPermissionContext permissionContext) {
        if (request == null) {
            return DocumentVisibilityContext.unrestricted();
        }
        if (permissionContext != null && permissionContext.isSuperAdmin() && visibilityRequested(request)) {
            return DocumentVisibilityContext.unrestricted();
        }
        List<String> selected = !safeList(request.selectedDocumentIds()).isEmpty()
            ? safeList(request.selectedDocumentIds())
            : safeList(request.selectedFileIds());
        return DocumentVisibilityContext.of(selected, request.documentVisibilityEnforced());
    }

    public boolean visibilityRequested(DocumentSearchRequest request) {
        return request != null
            && (!safeList(request.selectedDocumentIds()).isEmpty()
                || !safeList(request.selectedFileIds()).isEmpty()
                || Boolean.TRUE.equals(request.documentVisibilityEnforced()));
    }

    public boolean visibilityRequested(DocumentSearchExpandRequest request) {
        return request != null
            && (!safeList(request.selectedDocumentIds()).isEmpty()
                || !safeList(request.selectedFileIds()).isEmpty()
                || Boolean.TRUE.equals(request.documentVisibilityEnforced()));
    }

    public List<DocumentEvidenceChunk> visibleChunks(List<DocumentEvidenceChunk> chunks,
                                                     DocumentVisibilityContext visibilityContext) {
        if (chunks == null || chunks.isEmpty() || visibilityContext == null || !visibilityContext.active()) {
            return chunks == null ? List.of() : chunks;
        }
        return chunks.stream()
            .filter(chunk -> chunk != null && visibilityContext.allows(chunk.fileId()))
            .toList();
    }

    public List<DocumentSearchHit> visibleDocuments(List<DocumentSearchHit> documents,
                                                    DocumentVisibilityContext visibilityContext) {
        if (documents == null || documents.isEmpty() || visibilityContext == null || !visibilityContext.active()) {
            return documents == null ? List.of() : documents;
        }
        return documents.stream()
            .filter(document -> document != null && visibilityContext.allows(document.docId()))
            .toList();
    }

    public List<DocumentOutlineItem> visibleOutline(List<DocumentOutlineItem> outline,
                                                    DocumentVisibilityContext visibilityContext) {
        if (outline == null || outline.isEmpty() || visibilityContext == null || !visibilityContext.active()) {
            return outline == null ? List.of() : outline;
        }
        return outline.stream()
            .filter(item -> item != null && visibilityContext.allows(item.docId()))
            .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
