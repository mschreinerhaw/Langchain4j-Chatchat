package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Stable Runtime OS boundary for task-oriented knowledge acquisition. */
public interface KnowledgeRuntimePort extends RuntimeProtocolPort {
    KnowledgeContext retrieveKnowledge(KnowledgeRequest request);
}
