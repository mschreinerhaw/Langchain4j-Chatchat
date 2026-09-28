package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillRoleContext;

import java.util.Map;

/** Stable boundary for LangChain4j, Google ADK, OpenAI-compatible and external agent engines. */
public interface AgentRuntimeAdapter {
    String adapterId();
    default int priority() { return 0; }
    boolean supports(String engine);
    ExecutionResult execute(ExecutionRequest request);

    record ExecutionRequest(String engine, String query, SkillRoleContext roleContext,
                            ResolvedSkill skill, AuthorizedSkillScope scope,
                            WorkflowResolver.ResolvedWorkflow workflow,
                            Map<String, Object> attributes) {
        public ExecutionRequest { attributes = attributes == null ? Map.of() : Map.copyOf(attributes); }
    }

    record ExecutionResult(String status, String output, Map<String, Object> metadata) {
        public ExecutionResult { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
}
