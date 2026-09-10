package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Stable Runtime OS boundary for task-oriented knowledge acquisition. */
public interface KnowledgeRuntimePort extends RuntimeProtocolPort {
    KnowledgeContext retrieveKnowledge(KnowledgeRequest request);
}
