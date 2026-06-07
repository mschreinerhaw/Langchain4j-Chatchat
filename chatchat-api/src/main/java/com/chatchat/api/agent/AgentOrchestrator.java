package com.chatchat.api.agent;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.application.interaction.model.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.models.config.ModelsConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ModelsConfig modelsConfig;
    private final Map<String, ChatModel> chatModelsByName = new ConcurrentHashMap<>();

    public AgentExecutionResult executeAgent(String query,
                                             List<String> availableTools,
                                             String systemPrompt,
                                             String modelName,
                                             List<String> boundDocumentIds,
                                             List<String> boundDocumentTags,
                                             String skillId,
                                             String requestId,
                                             String conversationId,
                                             String userId) {
        List<String> tools = availableTools == null ? List.of() : availableTools;
        List<String> documentIds = normalizeList(boundDocumentIds);
        List<String> documentTags = normalizeList(boundDocumentTags);
        ChatModel activeChatModel = resolveChatModel(modelName);
        List<InteractionToolTrace> traces = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillId", skillId == null ? "general" : skillId);
        metadata.put("modelName", normalizeModelName(modelName));
        metadata.put("boundDocumentIds", documentIds);
        metadata.put("boundDocumentTags", documentTags);
        metadata.put("availableTools", tools);

        log.info("[{}] Agent orchestration started. tools={}", requestId, tools.size());

        for (int step = 1; step <= MAX_STEPS; step++) {
            AgentDecision decision = decideNextAction(activeChatModel, query, systemPrompt, tools, observations, documentIds, documentTags);
            metadata.put("steps", step);

            if (FINAL.equals(decision.action())) {
                String answer = safeAnswer(activeChatModel, decision.answer(), query, observations, systemPrompt);
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

            Map<String, Object> arguments = applyDocumentSearchDefaults(decision.toolName(), decision.arguments(), documentIds, documentTags);
            ToolInput toolInput = ToolInput.builder()
                .conversationId(conversationId)
                .requestId(requestId)
                .userId(userId)
                .parameters(arguments)
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
                .input(arguments)
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

        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
        metadata.put("stopReason", "max_steps_or_fallback");
        return new AgentExecutionResult(finalAnswer, traces, metadata);
    }

    private AgentDecision decideNextAction(ChatModel activeChatModel,
                                           String query,
                                           String systemPrompt,
                                           List<String> availableTools,
                                           List<String> observations,
                                           List<String> boundDocumentIds,
                                           List<String> boundDocumentTags) {
        String prompt = buildPlannerPrompt(query, systemPrompt, availableTools, observations, boundDocumentIds, boundDocumentTags);
        String raw = activeChatModel.chat(prompt);
        AgentDecision decision = parseDecision(raw);
        if (decision == null) {
            return new AgentDecision(FINAL, null, Map.of(), raw, "non_json_response");
        }
        return decision;
    }

    private String buildPlannerPrompt(String query,
                                      String systemPrompt,
                                      List<String> availableTools,
                                      List<String> observations,
                                      List<String> boundDocumentIds,
                                      List<String> boundDocumentTags) {
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
        if (!boundDocumentIds.isEmpty() || !boundDocumentTags.isEmpty()) {
            prompt.append("Knowledge document search scope:\n");
            if (!boundDocumentIds.isEmpty()) {
                prompt.append("- document_ids: ").append(boundDocumentIds).append("\n");
            }
            if (!boundDocumentTags.isEmpty()) {
                prompt.append("- tags: ").append(boundDocumentTags).append("\n");
            }
            prompt.append("Document workflow:\n");
            prompt.append("1. If the user asks about research material, reports, files, or document-backed facts, call document_search first.\n");
            prompt.append("2. Keep document_search within the configured document_ids/tags scope.\n");
            prompt.append("3. Use retrieved evidence as the basis of the final answer; if evidence is insufficient, say what is missing.\n");
            prompt.append("4. Do not invent facts beyond retrieved documents and tool observations.\n\n");
        }
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

    private String safeAnswer(ChatModel activeChatModel, String answer, String query, List<String> observations, String systemPrompt) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
    }

    private String summarizeWithObservations(ChatModel activeChatModel, String query, String systemPrompt, List<String> observations) {
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
        return activeChatModel.chat(prompt.toString());
    }

    private ChatModel resolveChatModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null || normalized.equals(modelsConfig.getDefaultChatModel())) {
            return chatModel;
        }
        if (!"openai".equalsIgnoreCase(modelsConfig.getDefaultProvider())) {
            return chatModel;
        }
        return chatModelsByName.computeIfAbsent(normalized, this::buildOpenAiChatModel);
    }

    private ChatModel buildOpenAiChatModel(String modelName) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .apiKey(modelsConfig.getOpenai().getApiKey())
            .baseUrl(modelsConfig.getOpenai().getBaseUrl())
            .modelName(modelName)
            .timeout(Duration.ofSeconds(modelsConfig.getOpenai().getTimeout()))
            .maxRetries(modelsConfig.getOpenai().getMaxRetries())
            .logRequests(true)
            .logResponses(true);
        HttpClientBuilder httpClientBuilder = resolveOpenAiHttpClientBuilder();
        if (httpClientBuilder != null) {
            builder.httpClientBuilder(httpClientBuilder);
        }
        return builder.build();
    }

    private HttpClientBuilder resolveOpenAiHttpClientBuilder() {
        ModelsConfig.ProxyConfig proxyConfig = modelsConfig.getOpenai().getProxy();
        if (proxyConfig == null || !proxyConfig.isEnabled()
            || proxyConfig.getHost() == null || proxyConfig.getHost().isBlank()
            || proxyConfig.getPort() == null || proxyConfig.getPort() <= 0) {
            return null;
        }
        if ("socks".equalsIgnoreCase(proxyConfig.getType())) {
            return null;
        }
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));
        return new JdkHttpClientBuilder().httpClientBuilder(httpClientBuilder);
    }

    private Map<String, Object> applyDocumentSearchDefaults(String toolName,
                                                            Map<String, Object> arguments,
                                                            List<String> boundDocumentIds,
                                                            List<String> boundDocumentTags) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Collections.emptyMap() : arguments);
        if (!"document_search".equals(toolName)) {
            return values;
        }
        if (!boundDocumentIds.isEmpty() && !values.containsKey("document_ids")) {
            values.put("document_ids", boundDocumentIds);
        }
        if (!boundDocumentTags.isEmpty() && !values.containsKey("tags")) {
            values.put("tags", boundDocumentTags);
        }
        return values;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String normalizeModelName(String modelName) {
        return modelName == null || modelName.isBlank() ? null : modelName.trim();
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
