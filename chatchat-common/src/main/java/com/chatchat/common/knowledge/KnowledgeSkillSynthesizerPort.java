package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Model-capable planner port. Implementations must return only whitelisted skill types. */
public interface KnowledgeSkillSynthesizerPort extends RuntimeProtocolPort {
    KnowledgeSkillPlan synthesize(KnowledgeRequest request);
}
