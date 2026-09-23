package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.skill.KnowledgeSkillPlan;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Deduplicates, ranks and compiles Knowledge IR under a hard Runtime context budget. */
public interface KnowledgeContextCompilerPort extends RuntimeProtocolPort {
    KnowledgeContext compile(KnowledgeRequest request, KnowledgeSkillPlan plan, List<KnowledgeIR> units);
}
