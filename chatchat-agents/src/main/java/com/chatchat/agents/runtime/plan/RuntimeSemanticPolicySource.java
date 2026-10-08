package com.chatchat.agents.runtime.plan;

/** Application adapter that snapshots the current database-owned Runtime OS vocabulary. */
public interface RuntimeSemanticPolicySource {
    RuntimeSemanticPolicy snapshot();
}
