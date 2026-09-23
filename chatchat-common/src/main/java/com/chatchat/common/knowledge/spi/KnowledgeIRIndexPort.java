package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.index.KnowledgeIndexDocument;
import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.index.KnowledgeIRQuery;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Persistence/search boundary for native normalized Knowledge IR indexes. */
public interface KnowledgeIRIndexPort extends RuntimeProtocolPort {
    void replaceDocument(KnowledgeIndexDocument document);
    void deleteDocument(String documentId);
    List<KnowledgeIR> search(KnowledgeIRQuery query);
}
