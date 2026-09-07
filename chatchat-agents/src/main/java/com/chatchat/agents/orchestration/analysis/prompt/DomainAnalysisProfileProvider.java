package com.chatchat.agents.orchestration.analysis.prompt;

import java.util.List;
import java.util.Map;

/** Host-supplied, tenant-scoped profiles. The analysis kernel has no built-in industry content. */
public interface DomainAnalysisProfileProvider {
    List<Profile> profiles(String tenantId);
    record Profile(String analysisType, String name, String description, long revision, Map<String, Object> guidance) {
        public Profile { guidance = Map.copyOf(guidance); }
        public Map<String, Object> catalogEntry() {
            return Map.of("analysisType", analysisType, "name", name, "description", description, "revision", revision);
        }
    }
    static DomainAnalysisProfileProvider empty() { return tenant -> List.of(); }
}
