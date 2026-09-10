package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Offline/ingestion boundary from a governed source into normalized Knowledge IR. */
public interface KnowledgeExtractionPort extends RuntimeProtocolPort {
    List<KnowledgeIR> extract(KnowledgeExtractionRequest request);
}
