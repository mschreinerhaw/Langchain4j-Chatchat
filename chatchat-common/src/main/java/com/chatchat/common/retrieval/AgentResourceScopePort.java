package com.chatchat.common.retrieval;

import java.util.Set;

/** Re-evaluated canonical user grants intersected with persisted Agent policy. */
public interface AgentResourceScopePort {
    Set<String> resolve(String tenant,String user,String agent,boolean explicitlyRequested);
}
