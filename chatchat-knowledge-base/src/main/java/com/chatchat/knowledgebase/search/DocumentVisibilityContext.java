package com.chatchat.knowledgebase.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record DocumentVisibilityContext(
    String contractVersion,
    Set<String> allowedFileIds,
    boolean enforced
) {

    public static final String CONTRACT_VERSION = "document_visibility_v1";

    public DocumentVisibilityContext {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        allowedFileIds = allowedFileIds == null ? Set.of() : Set.copyOf(allowedFileIds);
    }

    public static DocumentVisibilityContext unrestricted() {
        return new DocumentVisibilityContext(CONTRACT_VERSION, Set.of(), false);
    }

    public static DocumentVisibilityContext of(List<String> allowedFileIds, Boolean enforced) {
        Set<String> ids = cleanSet(allowedFileIds);
        boolean active = Boolean.TRUE.equals(enforced) || !ids.isEmpty();
        return new DocumentVisibilityContext(CONTRACT_VERSION, ids, active);
    }

    public boolean active() {
        return enforced;
    }

    public boolean allows(String fileId) {
        if (!active()) {
            return true;
        }
        return fileId != null && allowedFileIds.contains(fileId.trim());
    }

    public List<String> filterIds(List<String> ids) {
        if (!active()) {
            return ids == null ? List.of() : ids.stream().filter(this::hasText).map(String::trim).distinct().toList();
        }
        return (ids == null ? List.<String>of() : ids).stream()
            .filter(this::allows)
            .distinct()
            .toList();
    }

    private static Set<String> cleanSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                ids.add(value.trim());
            }
        }
        return ids;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
