package com.chatchat.api.agent;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.application.interaction.model.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent orchestrator with tool planning and execution loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final int MAX_STEPS = 3;
    private static final String FINAL = "final";
    private static final String TOOL = "tool";

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AgentExecutionResult executeAgent(String query,
                                             List<String> availableTools,
                                             String systemPrompt,
                                             String skillId,
                                             String requestId,
                                             String conversationId,
                                             String userId) {
        List<String> tools = availableTools == null ? List.of() : availableTools;
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("availableTools", tools);

        log.info("[{}] Agent orchestration started. tools={}", requestId, tools.size());

        for (int step = 1; step <= MAX_STEPS; step++) {
            AgentDecision decision = decideNextAction(query, systemPrompt, tools, observations);
            metadata.put("steps", step);

            if (FINAL.equals(decision.action())) {
                String answer = safeAnswer(decision.answer(), query, observations, systemPrompt);
                metadata.put("stopReason", "final_answer");
                return new AgentExecutionResult(answer, traces, metadata);
            }

            if (!TOOL.equals(decision.action())) {
                observations.add("Planner returned unsupported action, fallback to final answer.");
                break;
            }

            if (decision.toolName() == null || decision.toolName().isBlank()) {
                observations.add("Planner requested tool action without toolName.");
                break;
            }
            if (!tools.contains(decision.toolName())) {
                observations.add("Planner requested unavailable tool: " + decision.toolName());
                continue;
            }

            ToolInput toolInput = ToolInput.builder()
                .conversationId(conversationId)
                .requestId(requestId)
                .userId(userId)
                .parameters(decision.arguments() == null ? Map.of() : decision.arguments())
                .build();

            ToolMetadata toolMetadata = toolRegistry.getToolMetadata(decision.toolName());
            long startedAt = System.currentTimeMillis();
            ToolOutput output = toolRegistry.executeEnhancedTool(decision.toolName(), toolInput);
            long finishedAt = System.currentTimeMillis();
            String outputText = stringify(output.getData());
            Long durationMs = output.getExecutionTimeMs() == null ? Math.max(0L, finishedAt - startedAt) : output.getExecutionTimeMs();
            InteractionToolTrace trace = InteractionToolTrace.builder()
                .toolName(decision.toolName())
                .displayName(resolveDisplayName(decision.toolName(), toolMetadata))
                .serviceId(resolveServiceId(toolMetadata))
                .serviceName(resolveServiceName(toolMetadata))
                .success(output.isSuccess())
                .input(decision.arguments())
                .output(outputText)
                .errorMessage(output.getErrorMessage())
                .durationMs(durationMs)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .build();
            traces.add(trace);

            String observation = output.isSuccess()
                ? "Tool " + decision.toolName() + " output: " + outputText
                : "Tool " + decision.toolName() + " failed: " + output.getErrorMessage();
            observations.add(observation);
        }

        String finalAnswer = summarizeWithObservations(query, systemPrompt, observations);
        metadata.put("stopReason", "max_steps_or_fallback");
        return new AgentExecutionResult(finalAnswer, traces, metadata);
    }

    private AgentDecision decideNextAction(String query,
                                           String systemPrompt,
                                           List<String> availableTools,
                                           List<String> observations) {
        String prompt = buildPlannerPrompt(query, systemPrompt, availableTools, observations);
        String raw = chatModel.chat(prompt);
        AgentDecision decision = parseDecision(raw);
        if (decision == null) {
            return new AgentDecision(FINAL, null, Map.of(), raw, "non_json_response");
        }
        return decision;
    }

    private String buildPlannerPrompt(String query,
                                      String systemPrompt,
                                      List<String> availableTools,
                                      List<String> observations) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("You are an agent planner.\n");
        prompt.append("Goal: solve the user query with zero or more tools.\n");
        prompt.append("Respond with strict JSON only.\n");
        prompt.append("Schema:\n");
        prompt.append("{\"action\":\"final\",\"answer\":\"...\"}\n");
        prompt.append("or\n");
        prompt.append("{\"action\":\"tool\",\"toolName\":\"...\",\"arguments\":{...},\"reason\":\"...\"}\n\n");
        prompt.append("Available tools:\n").append(describeTools(availableTools)).append("\n");
        if (!observations.isEmpty()) {
            prompt.append("Observations so far:\n");
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
            prompt.append("\n");
        }
        prompt.append("User query:\n").append(query);
        return prompt.toString();
    }

    private String describeTools(List<String> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return "- (none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String toolName : availableTools) {
            ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
            if (metadata != null) {
                sb.append("- ")
                    .append(toolName)
                    .append(": ")
                    .append(metadata.getDescription())
                    .append("\n");
            } else {
                ToolRegistry.Tool simpleTool = toolRegistry.getTool(toolName);
                String description = simpleTool == null ? "No description available" : simpleTool.getDescription();
                sb.append("- ").append(toolName).append(": ").append(description).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private AgentDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            String action = stringValue(payload.get("action"));
            if (action == null) {
                return null;
            }
            action = action.toLowerCase(Locale.ROOT);
            if (FINAL.equals(action)) {
                return new AgentDecision(FINAL, null, Map.of(), stringValue(payload.get("answer")), stringValue(payload.get("reason")));
            }
            if (!TOOL.equals(action)) {
                return null;
            }
            Object argsObj = payload.get("arguments");
            Map<String, Object> arguments = argsObj instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return new AgentDecision(
                TOOL,
                stringValue(payload.get("toolName")),
                arguments,
                null,
                stringValue(payload.get("reason"))
            );
        } catch (Exception ex) {
            log.debug("Failed to parse planner decision: {}", raw, ex);
            return null;
        }
    }

    private String extractJson(String raw) {
        String text = raw.trim();
        int blockStart = text.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = text.indexOf('{', blockStart);
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String safeAnswer(String answer, String query, List<String> observations, String systemPrompt) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return summarizeWithObservations(query, systemPrompt, observations);
    }

    private String summarizeWithObservations(String query, String systemPrompt, List<String> observations) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("Use the observations below and answer the user question in Chinese.\n");
        if (observations.isEmpty()) {
            prompt.append("No external tool observation is available.\n");
        } else {
            observations.forEach(item -> prompt.append("- ").append(item).append("\n"));
        }
        prompt.append("\nUser question: ").append(query);
        return chatModel.chat(prompt.toString());
    }

    private String stringify(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String resolveDisplayName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return metadata.getTitle().trim();
        }
        return toolName;
    }

    private String resolveServiceId(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return null;
        }
        Object value = metadata.getMetadata().get("serviceId");
        return value == null ? null : String.valueOf(value);
    }

    private String resolveServiceName(ToolMetadata metadata) {
        if (metadata == null || metadata.getAuthor() == null || metadata.getAuthor().isBlank()) {
            return null;
        }
        String author = metadata.getAuthor().trim();
        if (author.startsWith("MCP:")) {
            return author.substring(4).trim();
        }
        return author;
    }

    private record AgentDecision(
        String action,
        String toolName,
        Map<String, Object> arguments,
        String answer,
        String reason
    ) {
    }

    public record AgentExecutionResult(
        String answer,
        List<InteractionToolTrace> toolTraces,
        Map<String, Object> metadata
    ) {
    }
}
