package com.chatchat.api.runtime.coding.nativecline;
/** Per-Activity control identity; never re-used across retry attempts. */
public interface NativeExecutionControl {
    String owner();
    int attempt();
    String workflowId();
    void heartbeat(String phase,String nodeId,String sessionId,String executionEpoch);
}
