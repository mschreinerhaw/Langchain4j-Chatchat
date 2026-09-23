package com.chatchat.common.knowledge.search;

import java.util.Map;

/** Common document contract returned by every knowledge-recall provider. */
public interface KnowledgeDocument {
    String documentId();
    String documentType();
    String title();
    String summary();
    Map<String, Object> attributes();
}
