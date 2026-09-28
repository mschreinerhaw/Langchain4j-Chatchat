package com.chatchat.common.retrieval;

import java.util.List;

/** Resolves the resource set an authenticated user may use through one Agent Skill. */
public interface SkillExecutionScopePort {
    String DENIED_DOCUMENT_ID = "__chatchat_denied_document_scope__";

    EffectiveScope resolve(String tenantId, String userId, String skillId,
                           List<String> legacyDocumentIds, List<String> legacyTags);

    record EffectiveScope(List<String> documentIds, List<String> tags, List<String> roles,
                          boolean managed, boolean skillAllowed) {
        public EffectiveScope {
            documentIds = List.copyOf(documentIds);
            tags = List.copyOf(tags);
            roles = List.copyOf(roles);
        }

        public static EffectiveScope denied(List<String> roles) {
            return new EffectiveScope(List.of(DENIED_DOCUMENT_ID), List.of(), roles, true, false);
        }
    }
}
