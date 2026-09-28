package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Executes the local LangChain4j runtime under the resolved database scope. */
@Component
public class LangChain4jSkillRuntimeAdapter implements AgentRuntimeAdapter {
    private final AgentRuntime runtime;

    public LangChain4jSkillRuntimeAdapter(AgentRuntime runtime) { this.runtime = runtime; }

    @Override public String adapterId() { return "langchain4j-agent-runtime"; }
    @Override public int priority() { return 100; }
    @Override public boolean supports(String engine) {
        String value = engine == null ? "" : engine.trim().toUpperCase(Locale.ROOT);
        return "LANGCHAIN4J".equals(value);
    }

    @Override public AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return new AgentRuntimeHealthResult("READY", Map.of("adapterId", adapterId(), "engine", "LANGCHAIN4J"));
    }

    @Override
    public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
        if (request == null || request.skill() == null || request.scope() == null
            || !request.scope().skillAllowed()) return new RuntimeAgentExecutionResult("SKILL_NOT_AUTHORIZED", "", Map.of());
        if (request.roleContext() == null)
            return new RuntimeAgentExecutionResult("ROLE_CONTEXT_REQUIRED", "", Map.of());
        Integer maxSteps = positiveInteger(request.attributes().get("maxSteps"));
        Integer maxToolCalls = positiveInteger(request.attributes().get("maxToolCalls"));
        Long timeoutMs = positiveLong(request.attributes().get("timeoutMs"));
        if (invalidNumber(request.attributes(), "maxSteps", maxSteps)
            || invalidNumber(request.attributes(), "maxToolCalls", maxToolCalls)
            || invalidNumber(request.attributes(), "timeoutMs", timeoutMs)) {
            return new RuntimeAgentExecutionResult("INVALID_RUNTIME_CONSTRAINT", "", Map.of(
                "message", "maxSteps, maxToolCalls and timeoutMs must be positive integers"));
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        attributes.put("skillRuntimeEngine", request.engine());
        if (request.workflow() != null) {
            attributes.put("workflowId", request.workflow().workflowId());
            attributes.put("workflowType", request.workflow().type().name());
        }
        AgentRunResult result = runtime.run(AgentRunRequest.builder()
            .runId(text(attributes.get("runId"), UUID.randomUUID().toString()))
            .requestId(text(attributes.get("requestId"), UUID.randomUUID().toString()))
            .conversationId(text(attributes.get("conversationId"), ""))
            .tenantId(request.roleContext().tenantId())
            .userId(request.roleContext().userId())
            .query(request.query())
            .skillId(request.skill().descriptor().id())
            .systemPrompt(request.skill().instructions())
            .modelName(text(attributes.get("modelName"), ""))
            .availableTools(request.scope().mcpToolIds())
            .requiredToolNames(request.scope().mcpToolIds())
            .boundDocumentIds(request.scope().documentIds())
            .boundDocumentTags(request.scope().knowledgeBaseIds())
            .maxSteps(maxSteps)
            .maxToolCalls(maxToolCalls)
            .timeoutMs(timeoutMs == null ? 0L : timeoutMs)
            .attributes(attributes)
            .build());
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("runId", result.runId());
        metadata.put("stopReason", result.stopReason() == null ? "" : result.stopReason());
        if (result.errorMessage() != null && !result.errorMessage().isBlank())
            metadata.put("error", result.errorMessage());
        return new RuntimeAgentExecutionResult(result.status().name(), result.answer(), metadata);
    }

    private String text(Object value, String fallback) {
        String result = value == null ? "" : value.toString().trim();
        return result.isBlank() ? fallback : result;
    }
    private Integer positiveInteger(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
            return parsed > 0 ? parsed : null;
        }
        catch (NumberFormatException ignored) { return null; }
    }
    private Long positiveLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
            return parsed > 0L ? parsed : null;
        }
        catch (NumberFormatException ignored) { return null; }
    }
    private boolean invalidNumber(Map<String, Object> attributes, String key, Number parsed) {
        Object raw = attributes.get(key);
        return raw != null && !raw.toString().isBlank() && parsed == null;
    }
}
