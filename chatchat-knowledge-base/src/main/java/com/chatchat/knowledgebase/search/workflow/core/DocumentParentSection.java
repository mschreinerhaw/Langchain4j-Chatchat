package com.chatchat.knowledgebase.search.workflow;

/** PostgreSQL navigation metadata used to expand a ranked passage to its parent section. */
public record DocumentParentSection(
    String documentId,
    String knowledgeId,
    String chunkId,
    String section,
    String title,
    String citation
) { }
