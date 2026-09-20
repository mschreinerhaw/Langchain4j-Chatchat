package com.chatchat.chat.skills.domain.adapter;

/** Compiles untrusted, format-neutral material into the platform-owned runtime contract. */
public interface ExternalSkillCompiler {
    RuntimeSkillIr compile(AdaptedExternalSkill skill);
}
