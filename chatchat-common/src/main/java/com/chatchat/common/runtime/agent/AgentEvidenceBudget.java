package com.chatchat.common.runtime.agent;

/** Shared evidence envelope budget for acquisition and local/remote Agent handoff. */
public final class AgentEvidenceBudget {
    public static final int ITEM_CHARS = 65_536;
    public static final int TOTAL_CHARS = 150_000;
    public static final int MAX_ITEMS = 24;
    private AgentEvidenceBudget() { }
}
