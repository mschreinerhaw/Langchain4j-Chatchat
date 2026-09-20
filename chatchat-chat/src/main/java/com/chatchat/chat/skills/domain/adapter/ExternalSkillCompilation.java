package com.chatchat.chat.skills.domain.adapter;

/** Immutable output of the import/compile boundary, including artifacts needed for future recompilation. */
public record ExternalSkillCompilation(
    RuntimeSkillIr skillIr,
    byte[] originalArtifact,
    String originalHash,
    String parsedDocumentJson,
    String skillIrJson,
    String sourceReference
) {
    public ExternalSkillCompilation {
        originalArtifact = originalArtifact == null ? new byte[0] : originalArtifact.clone();
    }

    @Override
    public byte[] originalArtifact() { return originalArtifact.clone(); }
}
