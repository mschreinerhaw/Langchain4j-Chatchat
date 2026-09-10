package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Runtime executor for one statically whitelisted knowledge capability type. */
public interface KnowledgeSkillExecutorPort extends RuntimeProtocolPort {
    boolean supports(KnowledgeSkillType skillType);
    KnowledgeSkillResult execute(KnowledgeSkillExecutionContext context);
}
