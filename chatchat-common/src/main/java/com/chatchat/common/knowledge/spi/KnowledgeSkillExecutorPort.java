package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.skill.KnowledgeSkillResult;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Runtime executor for one statically whitelisted knowledge capability type. */
public interface KnowledgeSkillExecutorPort extends RuntimeProtocolPort {
    boolean supports(KnowledgeSkillType skillType);
    KnowledgeSkillResult execute(KnowledgeSkillExecutionContext context);
}
