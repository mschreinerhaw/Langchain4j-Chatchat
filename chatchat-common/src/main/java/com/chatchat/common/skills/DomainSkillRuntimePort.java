package com.chatchat.common.skills;

import java.util.List;
import java.util.Map;

/** Resolves published domain skills without coupling chat runtime to the API persistence module. */
public interface DomainSkillRuntimePort {
    /** Run-scoped projection consumed by the planner; it must contain published skills only. */
    String PLANNING_CONTEXT_ATTRIBUTE = "domainSkillPlanningContext";

    List<DomainSkillContent> resolvePublished(String tenantId, List<String> skillIds);

    default List<DomainSkillContent> retrievePublished(String tenantId, String userId,
                                                       List<String> roles, String query,
                                                       List<String> skillIds) {
        return resolvePublished(tenantId, skillIds);
    }

    /**
     * Selects published, authorized domain skills from untrusted evidence previews.
     * Implementations must never treat preview text as executable instructions or
     * use it to grant tools, data access, or permissions.
     */
    default EvidenceSkillActivation activateForEvidence(String tenantId, String userId,
                                                         List<String> roles, String query,
                                                         List<EvidencePreview> previews,
                                                         int maxActivatedSkills) {
        return EvidenceSkillActivation.empty("UNSUPPORTED");
    }

    record DomainSkillContent(String id, String name, String category, String markdownContent,
                              String sourceType, String sourceId, String sourceUri, String sourceDigest) {
        public DomainSkillContent(String id, String name, String category, String markdownContent) {
            this(id, name, category, markdownContent, "LOCAL", "", "", "");
        }

        public DomainSkillContent {
            sourceType = clean(sourceType);
            sourceId = clean(sourceId);
            sourceUri = clean(sourceUri);
            sourceDigest = clean(sourceDigest);
        }
    }

    record EvidencePreview(String evidenceId, String documentId, String documentName,
                           String section, String content) {
        public EvidencePreview {
            evidenceId = clean(evidenceId);
            documentId = clean(documentId);
            documentName = clean(documentName);
            section = clean(section);
            content = content == null ? "" : content;
        }
    }

    record EvidenceSkillActivation(List<DomainSkillContent> candidates,
                                   List<DomainSkillContent> activated,
                                   Map<String, Object> planningKnowledge,
                                   String compiledContext,
                                   String status,
                                   String error) {
        public EvidenceSkillActivation {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            activated = activated == null ? List.of() : List.copyOf(activated);
            planningKnowledge = planningKnowledge == null ? Map.of() : Map.copyOf(planningKnowledge);
            compiledContext = compiledContext == null ? "" : compiledContext;
            status = clean(status);
            error = clean(error);
        }

        public static EvidenceSkillActivation empty(String status) {
            return new EvidenceSkillActivation(List.of(), List.of(), Map.of(), "", status, null);
        }

        public List<String> activatedSkillIds() {
            return activated.stream().map(DomainSkillContent::id).toList();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
