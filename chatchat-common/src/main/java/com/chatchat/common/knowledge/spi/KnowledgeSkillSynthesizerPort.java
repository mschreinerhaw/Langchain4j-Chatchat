package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.skill.KnowledgeSkillPlan;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Model-capable planner port. Implementations must return only whitelisted skill types. */
public interface KnowledgeSkillSynthesizerPort extends RuntimeProtocolPort {
    KnowledgeSkillPlan synthesize(KnowledgeRequest request);
}
