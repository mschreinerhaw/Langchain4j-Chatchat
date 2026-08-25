package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.runtime.GovernanceIsolationScope;

import java.util.Map;

/** Minimal evidence envelope visible to Runtime protocol consumers. */
public interface RuntimeEvidenceEnvelope {
    String schemaVersion();
    String evidenceId();
    String toolName();
    String outcome();
    GovernanceIsolationScope isolationScope();
    Object payload();
    Map<String, Object> governance();
    Map<String, Object> descriptor();
}
