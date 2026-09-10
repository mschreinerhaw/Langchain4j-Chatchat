package com.chatchat.common.knowledge;

/** Auditable lineage from Knowledge IR back to its governed source. */
public record KnowledgeSourceReference(
    String sourceId,
    String documentId,
    String chunkId,
    String documentName,
    String section,
    String version,
    String citation
) {
}
