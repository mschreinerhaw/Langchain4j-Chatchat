package com.chatchat.common.skills;

import java.util.List;

/** Resolves published domain skills without coupling chat runtime to the API persistence module. */
public interface DomainSkillRuntimePort {
    List<DomainSkillContent> resolvePublished(String tenantId, List<String> skillIds);

    record DomainSkillContent(String id, String name, String category, String markdownContent) { }
}
