package com.chatchat.common.skills;

import java.util.List;

/** Resolves published domain skills without coupling chat runtime to the API persistence module. */
public interface DomainSkillRuntimePort {
    /** Run-scoped projection consumed by the planner; it must contain published skills only. */
    String PLANNING_CONTEXT_ATTRIBUTE = "domainSkillPlanningContext";

    List<DomainSkillContent> resolvePublished(String tenantId, List<String> skillIds);

    record DomainSkillContent(String id, String name, String category, String markdownContent) { }
}
