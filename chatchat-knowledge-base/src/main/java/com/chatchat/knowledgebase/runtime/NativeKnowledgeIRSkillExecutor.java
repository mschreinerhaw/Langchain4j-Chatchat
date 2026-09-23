package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.spi.KnowledgeIRIndexPort;
import com.chatchat.common.knowledge.index.KnowledgeIRQuery;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.spi.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.skill.KnowledgeSkillResult;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preferred executor over the native normalized Knowledge IR index. */
@Component
@Order(0)
@RequiredArgsConstructor
public class NativeKnowledgeIRSkillExecutor implements KnowledgeSkillExecutorPort {

    private final KnowledgeIRIndexPort index;

    @Override
    public boolean supports(KnowledgeSkillType skillType) {
        return skillType != null;
    }

    @Override
    public KnowledgeSkillResult execute(KnowledgeSkillExecutionContext context) {
        List<KnowledgeIR> units = index.search(new KnowledgeIRQuery(
            context.request().scope(), Set.of(context.skill().skillType().knowledgeType()),
            context.skill().goal(), context.skill().queryHints(), 8));
        return new KnowledgeSkillResult(
            context.skill().instanceId(), context.skill().skillType(), units,
            units.isEmpty() ? "empty" : "used", Map.of("adapter", "native-knowledge-ir"));
    }
}
