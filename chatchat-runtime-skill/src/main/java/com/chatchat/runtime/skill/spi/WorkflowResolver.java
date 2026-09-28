package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillRoleContext;

import java.util.List;
import java.util.Map;

public interface WorkflowResolver {
    WorkflowResolution resolve(ResolvedSkill skill, AuthorizedSkillScope scope,
                               SkillRoleContext roleContext, Map<String, Object> intent);

    record WorkflowResolution(ResolvedWorkflow workflow, String status, Map<String, Object> diagnostics) {
        public WorkflowResolution {
            status = status == null || status.isBlank() ? "UNRESOLVED" : status.trim();
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
        public boolean resolved() { return workflow != null; }
    }

    record ResolvedWorkflow(String workflowId, WorkflowType type, List<String> requiredCapabilities,
                            Map<String, Object> configuration) {
        public ResolvedWorkflow {
            requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        }
    }

    enum WorkflowType { DOCUMENT, DATA_ANALYSIS, EXTERNAL_AGENT, EVIDENCE, REMEDIATION }
}
