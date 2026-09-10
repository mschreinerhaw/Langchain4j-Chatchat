package com.chatchat.common.knowledge;

/** Validated input passed from Knowledge Runtime to a whitelisted skill executor. */
public record KnowledgeSkillExecutionContext(
    KnowledgeRequest request,
    KnowledgeSkillInstance skill
) {
    public KnowledgeSkillExecutionContext {
        if (request == null) throw new IllegalArgumentException("knowledge request is required");
        if (skill == null) throw new IllegalArgumentException("knowledge skill is required");
        if (!request.allowedSkillTypes().contains(skill.skillType())) {
            throw new IllegalArgumentException("knowledge skill is outside the request whitelist");
        }
    }
}
