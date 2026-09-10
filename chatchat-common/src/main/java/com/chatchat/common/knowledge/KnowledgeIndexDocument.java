package com.chatchat.common.knowledge;

import java.util.List;

/** Versioned, permission-carrying batch written to a Knowledge IR index. */
public record KnowledgeIndexDocument(
    String documentId,
    String tenantId,
    String ownerUserId,
    String visibility,
    List<String> permissionRoles,
    List<String> tags,
    String version,
    List<KnowledgeIR> units
) {
    public KnowledgeIndexDocument {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId is required");
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        ownerUserId = ownerUserId == null || ownerUserId.isBlank() ? "anonymous" : ownerUserId.trim();
        visibility = visibility == null || visibility.isBlank() ? "tenant" : visibility.trim().toLowerCase();
        permissionRoles = permissionRoles == null ? List.of() : List.copyOf(permissionRoles);
        tags = tags == null ? List.of() : List.copyOf(tags);
        version = version == null ? "" : version.trim();
        units = units == null ? List.of() : List.copyOf(units);
    }
}
