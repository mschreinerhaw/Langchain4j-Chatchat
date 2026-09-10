package com.chatchat.common.knowledge;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Deduplicates, ranks and compiles Knowledge IR under a hard Runtime context budget. */
public interface KnowledgeContextCompilerPort extends RuntimeProtocolPort {
    KnowledgeContext compile(KnowledgeRequest request, KnowledgeSkillPlan plan, List<KnowledgeIR> units);
}
