package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record DocumentSelectionContext(
    String contractVersion,
    Set<String> allowedDocumentIds,
    boolean enforced
) {

    public static final String CONTRACT_VERSION = "document_visibility_v1";

    public DocumentSelectionContext {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        allowedDocumentIds = allowedDocumentIds == null ? Set.of() : Set.copyOf(allowedDocumentIds);
    }

    public static DocumentSelectionContext unrestricted() {
        return new DocumentSelectionContext(CONTRACT_VERSION, Set.of(), false);
    }

    public static DocumentSelectionContext of(Collection<?> ids, boolean enforced) {
        Set<String> values = cleanSet(ids);
        return new DocumentSelectionContext(CONTRACT_VERSION, values, enforced || !values.isEmpty());
    }

    public static DocumentSelectionContext fromToolData(Object data) {
        Map<?, ?> root = data instanceof Map<?, ?> map ? map : Map.of();
        if (hasSuperAdminRole(root)) {
            return unrestricted();
        }
        List<Object> ids = firstList(root,
            "selectedDocumentIds",
            "selected_document_ids",
            "selectedFileIds",
            "selected_file_ids",
            "allowedDocIds",
            "allowed_doc_ids",
            "allowedDocumentIds",
            "allowed_document_ids"
        );
        boolean explicit = !ids.isEmpty();
        if (!explicit && strictScope(root)) {
            ids = firstList(root, "document_ids", "documentIds", "fileIds", "file_ids");
            explicit = !ids.isEmpty();
        }
        boolean enforced = explicit || boolValue(firstPresent(root,
            "documentVisibilityEnforced",
            "document_visibility_enforced",
            "strict_document_scope",
            "strictDocumentScope"
        ));
        return of(ids, enforced);
    }

    public boolean active() {
        return enforced;
    }

    public boolean allows(EvidenceChunk chunk) {
        if (!active() || chunk == null || chunk.evidenceType() != EvidenceType.DOCUMENT) {
            return true;
        }
        String docId = documentId(chunk);
        return docId != null && allowedDocumentIds.contains(docId);
    }

    public boolean allowsNode(EvidenceGraphNode node) {
        if (!active() || node == null) {
            return true;
        }
        Object evidenceType = node.metadata().get("evidenceType");
        if (evidenceType != null && !"DOCUMENT".equalsIgnoreCase(String.valueOf(evidenceType))) {
            return true;
        }
        String docId = firstNonBlank(
            stringValue(node.metadata().get("fileId")),
            documentIdFromSourceRef(node.sourceRef())
        );
        return docId != null && allowedDocumentIds.contains(docId);
    }

    public FilterResult filter(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new FilterResult(List.of(), 0, this);
        }
        if (!active()) {
            return new FilterResult(List.copyOf(chunks), 0, this);
        }
        List<EvidenceChunk> visible = new ArrayList<>();
        int discarded = 0;
        for (EvidenceChunk chunk : chunks) {
            if (allows(chunk)) {
                visible.add(chunk);
            } else {
                discarded++;
            }
        }
        return new FilterResult(List.copyOf(visible), discarded, this);
    }

    public String documentId(EvidenceChunk chunk) {
        if (chunk == null) {
            return null;
        }
        String fromSource = chunk.source() == null ? null : chunk.source().fileId();
        String fromCitation = firstNonBlank(
            stringValue(chunk.citation().get("fileId")),
            firstNonBlank(
                stringValue(chunk.citation().get("docId")),
                stringValue(chunk.citation().get("documentId"))
            )
        );
        return firstNonBlank(fromSource, firstNonBlank(fromCitation, documentIdFromSourceRef(stringValue(chunk.citation().get("refId")))));
    }

    private static List<Object> firstList(Map<?, ?> root, String... keys) {
        Object value = firstPresent(root, keys);
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split("[,;\\s]+")).map(item -> (Object) item).toList();
        }
        return List.of();
    }

    private static Object firstPresent(Map<?, ?> root, String... keys) {
        if (root == null || root.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (root.containsKey(key)) {
                return root.get(key);
            }
        }
        return null;
    }

    private static boolean strictScope(Map<?, ?> root) {
        Object value = firstPresent(root, "strict_document_scope", "strictDocumentScope");
        if (boolValue(value)) {
            return true;
        }
        Object mode = firstPresent(root, "scope_mode", "scopeMode");
        return mode != null && "strict".equalsIgnoreCase(String.valueOf(mode).trim());
    }

    private static boolean hasSuperAdminRole(Map<?, ?> root) {
        List<Object> roles = new ArrayList<>();
        roles.addAll(firstList(root, "roles", "role"));
        Object requestContext = firstPresent(root, "requestContext", "request_context", "context");
        if (requestContext instanceof Map<?, ?> context) {
            roles.addAll(firstList(context, "roles", "role"));
        }
        return roles.stream()
            .map(DocumentSelectionContext::stringValue)
            .map(DocumentSelectionContext::normalizeRole)
            .anyMatch(DocumentSelectionContext::isSuperAdminRole);
    }

    private static boolean isSuperAdminRole(String role) {
        return Set.of(
            "superadmin",
            "rolesuperadmin",
            "superadministrator",
            "rolesuperadministrator",
            "超级管理员"
        ).contains(role);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        return role.trim()
            .toLowerCase(Locale.ROOT)
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "");
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static Set<String> cleanSet(Collection<?> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object id : ids) {
            String text = stringValue(id);
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private static String documentIdFromSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank() || !sourceRef.startsWith("doc://")) {
            return null;
        }
        String value = sourceRef.substring("doc://".length());
        int hash = value.indexOf('#');
        return hash >= 0 ? value.substring(0, hash) : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : (second == null || second.isBlank() ? null : second.trim());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record FilterResult(
        List<EvidenceChunk> visibleChunks,
        int discardedChunks,
        DocumentSelectionContext context
    ) {
        public FilterResult {
            visibleChunks = visibleChunks == null ? List.of() : List.copyOf(visibleChunks);
            context = context == null ? DocumentSelectionContext.unrestricted() : context;
        }
    }
}
