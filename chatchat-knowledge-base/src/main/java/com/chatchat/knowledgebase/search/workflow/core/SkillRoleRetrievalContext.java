package com.chatchat.knowledgebase.search.workflow;

import java.util.List;

/** Skill document bindings and database-resolved caller role context. */
public record SkillRoleRetrievalContext(
    List<String> documentIds,
    List<String> documentTags,
    List<String> roles
) {
    public SkillRoleRetrievalContext {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        documentTags = documentTags == null ? List.of() : List.copyOf(documentTags);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
