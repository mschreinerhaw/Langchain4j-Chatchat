package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.skill.KnowledgeSkillResult;
import com.chatchat.common.knowledge.spi.KnowledgeSkillExecutorPort;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Dispatches only to the existing statically whitelisted Knowledge Skill executors. */
@Component
public class KnowledgeSkillExecutionUnit implements ExecutionUnit<KnowledgeSkillExecutionContext, KnowledgeSkillResult> {
    private final ObjectProvider<KnowledgeSkillExecutorPort> executors;

    public KnowledgeSkillExecutionUnit(ObjectProvider<KnowledgeSkillExecutorPort> executors) {
        this.executors = executors;
    }

    @Override public ComputeNodeType nodeType() { return ComputeNodeType.SKILL; }
    @Override public Class<KnowledgeSkillExecutionContext> inputType() { return KnowledgeSkillExecutionContext.class; }
    @Override public Class<KnowledgeSkillResult> outputType() { return KnowledgeSkillResult.class; }

    @Override public KnowledgeSkillResult execute(KnowledgeSkillExecutionContext input, KernelDataScope scope) {
        if (input == null || scope == null
            || !scope.tenantId().equals(input.request().scope().tenantId())
            || !scope.userId().equals(input.request().scope().userId()))
            throw new IllegalArgumentException("Skill execution scope mismatch");
        List<KnowledgeSkillExecutorPort> matching = executors.orderedStream()
            .filter(executor -> executor.supports(input.skill().skillType())).toList();
        if (matching.isEmpty())
            throw new IllegalStateException("No authorized executor for " + input.skill().skillType());
        KnowledgeSkillResult last = null;
        for (KnowledgeSkillExecutorPort executor : matching) {
            last = executor.execute(input);
            if (last != null && !last.knowledgeUnits().isEmpty()) return last;
        }
        return last == null ? KnowledgeSkillResult.empty(input.skill(), "empty") : last;
    }
}
