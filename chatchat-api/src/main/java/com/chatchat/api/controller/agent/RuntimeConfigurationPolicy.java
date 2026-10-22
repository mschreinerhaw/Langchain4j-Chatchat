package com.chatchat.api.controller.agent;
import java.util.*;

/** Preserve server-owned runtime settings when a customer changes business configuration. */
final class RuntimeConfigurationPolicy {
    private static final Set<String> INTERNAL=Set.of("executionStrategy","coding","codingAgent","pythonSandbox",
        "sandbox","plugins","pluginClasses","snapshotStorage","temporaryDirectory","containerOptions");
    private RuntimeConfigurationPolicy() { }
    static Map<String,Object> forUser(Map<String,Object> requested,Map<String,Object> persisted) {
        var result=new LinkedHashMap<>(requested==null?Map.of():requested);
        for(String key:INTERNAL) {
            result.remove(key);
            if(persisted!=null && persisted.containsKey(key))result.put(key,persisted.get(key));
        }
        // Existing service configurations retain their exact budgets; new services use stable defaults.
        result.putIfAbsent("executionStrategy",Map.of("mode","sequential","stopOnError",true,"maxSteps",3,
            "costBudget",10,"latencyBudgetMs",1800000,"toolRetryAttempts",3,"allowParallel",false));
        return result;
    }
}
