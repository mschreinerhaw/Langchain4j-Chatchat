package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeContext;
import com.chatchat.common.knowledge.KnowledgeContextCompilerPort;
import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeRuntimePort;
import com.chatchat.common.knowledge.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.KnowledgeSkillExecutorPort;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillResult;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Planner/executor/compiler orchestration behind the stable Knowledge Runtime port. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultKnowledgeRuntimeService implements KnowledgeRuntimePort {

    private final KnowledgeSkillSynthesizerPort skillSynthesizer;
    private final List<KnowledgeSkillExecutorPort> skillExecutors;
    private final KnowledgeContextCompilerPort contextCompiler;

    @Override
    public KnowledgeContext retrieveKnowledge(KnowledgeRequest request) {
        KnowledgeSkillPlan plan = skillSynthesizer.synthesize(request);
        List<KnowledgeIR> units = new ArrayList<>();
        for (KnowledgeSkillInstance skill : plan.skills()) {
            boolean handled = false;
            for (KnowledgeSkillExecutorPort executor : findExecutors(skill)) {
                try {
                    KnowledgeSkillResult result = executor.execute(
                        new KnowledgeSkillExecutionContext(request, skill));
                    if (result != null && !result.knowledgeUnits().isEmpty()) {
                        units.addAll(result.knowledgeUnits());
                        handled = true;
                        break;
                    }
                } catch (RuntimeException ex) {
                    log.warn("knowledgeSkillExecutionFailed instanceId={} type={} executor={} error={}",
                        skill.instanceId(), skill.skillType(), executor.getClass().getSimpleName(), ex.getMessage());
                }
            }
            if (!handled) log.warn("knowledgeSkillEvidenceMissing instanceId={} type={}",
                skill.instanceId(), skill.skillType());
        }
        return contextCompiler.compile(request, plan, units);
    }

    private List<KnowledgeSkillExecutorPort> findExecutors(KnowledgeSkillInstance skill) {
        return skillExecutors.stream().filter(executor -> executor.supports(skill.skillType())).toList();
    }
}
