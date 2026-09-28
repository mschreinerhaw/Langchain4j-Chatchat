package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Executes local LangChain4j and OpenAI-compatible models under the resolved database scope. */
@Component
public class LangChain4jSkillRuntimeAdapter implements AgentRuntimeAdapter {
    private final AgentRuntime runtime;

    public LangChain4jSkillRuntimeAdapter(AgentRuntime runtime) { this.runtime = runtime; }

    @Override public String adapterId() { return "langchain4j-agent-runtime"; }
    @Override public int priority() { return 100; }
    @Override public boolean supports(String engine) {
        String value = engine == null ? "" : engine.trim().toUpperCase(Locale.ROOT);
        return "LANGCHAIN4J".equals(value) || "OPENAI_COMPATIBLE".equals(value);
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        if (request == null || request.skill() == null || request.scope() == null
            || !request.scope().skillAllowed()) return new ExecutionResult("SKILL_NOT_AUTHORIZED", "", Map.of());
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
            .maxSteps(integer(attributes.get("maxSteps")))
            .maxToolCalls(integer(attributes.get("maxToolCalls")))
            .timeoutMs(longValue(attributes.get("timeoutMs")))
            .attributes(attributes)
            .build());
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("runId", result.runId());
        metadata.put("stopReason", result.stopReason() == null ? "" : result.stopReason());
        if (result.errorMessage() != null && !result.errorMessage().isBlank())
            metadata.put("error", result.errorMessage());
        return new ExecutionResult(result.status().name(), result.answer(), metadata);
    }

    private String text(Object value, String fallback) {
        String result = value == null ? "" : value.toString().trim();
        return result.isBlank() ? fallback : result;
    }
    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try { return value == null ? 0L : Math.max(0L, Long.parseLong(value.toString())); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
