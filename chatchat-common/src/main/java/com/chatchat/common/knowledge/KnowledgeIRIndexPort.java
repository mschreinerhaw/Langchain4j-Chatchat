package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Persistence/search boundary for native normalized Knowledge IR indexes. */
public interface KnowledgeIRIndexPort extends RuntimeProtocolPort {
    void replaceDocument(KnowledgeIndexDocument document);
    void deleteDocument(String documentId);
    List<KnowledgeIR> search(KnowledgeIRQuery query);
}
