package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.extraction.KnowledgeExtractionRequest;
import com.chatchat.common.knowledge.model.KnowledgeIR;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Offline/ingestion boundary from a governed source into normalized Knowledge IR. */
public interface KnowledgeExtractionPort extends RuntimeProtocolPort {
    List<KnowledgeIR> extract(KnowledgeExtractionRequest request);
}
