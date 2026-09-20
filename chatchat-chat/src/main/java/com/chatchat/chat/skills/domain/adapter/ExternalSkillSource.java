package com.chatchat.chat.skills.domain.adapter;

/** Untrusted external material entering the skill adaptation boundary. */
public record ExternalSkillSource(
    String fileName,
    String sourceType,
    String sourceReference,
    String content,
    byte[] originalArtifact
) {
    public ExternalSkillSource(String fileName, String sourceType, String sourceReference, String content) {
        this(fileName, sourceType, sourceReference, content, new byte[0]);
    }

    public ExternalSkillSource {
        originalArtifact = originalArtifact == null ? new byte[0] : originalArtifact.clone();
    }

    @Override
    public byte[] originalArtifact() {
        return originalArtifact.clone();
    }
}
