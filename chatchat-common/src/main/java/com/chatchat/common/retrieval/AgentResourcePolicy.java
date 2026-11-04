package com.chatchat.common.retrieval;

import java.util.*;

/** Persisted business choices, never physical indexes or storage credentials. */
public record AgentResourcePolicy(Set<String> enabled, String selectionStrategy, boolean configured) {
    public static final String KEY="resourceExploration", GRANT_TYPE=ResourceAuthorizationPort.DATA_RESOURCE;
    public static final String KNOWLEDGE="knowledge_documents", NEWS="financial_news", MARKET="financial_market";
    public static final String INTERNET="internet_search";
    public static final Set<String> IDS=Set.of(KNOWLEDGE,NEWS,MARKET,INTERNET);
    public AgentResourcePolicy { enabled=Set.copyOf(enabled); }
    public static AgentResourcePolicy read(Map<String,Object> workflow) {
        if(workflow==null||!workflow.containsKey(KEY))return new AgentResourcePolicy(Set.of(KNOWLEDGE),"task_driven",false);
        if(!(workflow.get(KEY) instanceof Map<?,?> value))throw new IllegalArgumentException("Invalid data resource policy");
        if(!Set.of("resourcePermissions","selectionStrategy").containsAll(value.keySet()))throw new IllegalArgumentException("Unknown resource policy field");
        String strategy=Objects.toString(value.get("selectionStrategy"),"task_driven");
        if(!Set.of("task_driven","explicit_request").contains(strategy))throw new IllegalArgumentException("Invalid resource selection strategy");
        var ids=new LinkedHashSet<String>();var seen=new HashSet<String>();
        if(!(value.get("resourcePermissions") instanceof List<?> entries)||entries.size()>4)throw new IllegalArgumentException("Expected bounded resource permissions");
        for(Object entry:entries) {
            if(!(entry instanceof Map<?,?> row)||!Set.of("resourceId","enabled","access").containsAll(row.keySet()))throw new IllegalArgumentException("Invalid resource permission");
            String id=Objects.toString(row.get("resourceId"),"");
            if(!IDS.contains(id)||!seen.add(id)||!"read".equals(row.get("access"))||!(row.get("enabled") instanceof Boolean))throw new IllegalArgumentException("Only known read-only resources are supported");
            if(Boolean.TRUE.equals(row.get("enabled")))ids.add(id);
        }
        return new AgentResourcePolicy(ids,strategy,true);
    }
    public boolean permits(String id,boolean explicitlyRequested) {
        return enabled.contains(id)&&(!"explicit_request".equals(selectionStrategy)||explicitlyRequested);
    }
}
