package com.chatchat.chat.skills.domain.adapter;

/** Adapts one external skill document format without executing anything from it. */
public interface ExternalSkillAdapter {
    boolean supports(ExternalSkillSource source);

    AdaptedExternalSkill adapt(ExternalSkillSource source);
}
