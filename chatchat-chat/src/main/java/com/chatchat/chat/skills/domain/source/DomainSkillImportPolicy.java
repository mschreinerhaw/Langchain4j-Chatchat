package com.chatchat.chat.skills.domain.source;

/** Shared safety limits for local and remote Skill imports. */
public final class DomainSkillImportPolicy {
    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private DomainSkillImportPolicy() {
    }
}
