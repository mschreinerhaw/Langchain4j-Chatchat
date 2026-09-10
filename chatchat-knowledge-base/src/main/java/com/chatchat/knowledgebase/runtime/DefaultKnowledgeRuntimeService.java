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
            findExecutor(skill).ifPresentOrElse(executor -> {
                try {
                    KnowledgeSkillResult result = executor.execute(
                        new KnowledgeSkillExecutionContext(request, skill));
                    if (result != null) units.addAll(result.knowledgeUnits());
                } catch (RuntimeException ex) {
                    log.warn("knowledgeSkillExecutionFailed instanceId={} type={} error={}",
                        skill.instanceId(), skill.skillType(), ex.getMessage());
                }
            }, () -> log.warn("knowledgeSkillExecutorMissing instanceId={} type={}",
                skill.instanceId(), skill.skillType()));
        }
        return contextCompiler.compile(request, plan, units);
    }

    private java.util.Optional<KnowledgeSkillExecutorPort> findExecutor(KnowledgeSkillInstance skill) {
        return skillExecutors.stream().filter(executor -> executor.supports(skill.skillType())).findFirst();
    }
}
