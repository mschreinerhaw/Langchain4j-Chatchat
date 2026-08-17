package com.chatchat.agents.orchestration;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adds at most one bounded web-retrieval round before final answer review.
 * The WSA provider remains hidden behind the existing governed web_search tool.
 */
@Slf4j
class FinalSummaryWebSearchEnhancer {
    static final String CANDIDATE_STAGE = "final_summary_web_enhancement";

    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties properties;
    private final AgentToolNameResolver toolNames = new AgentToolNameResolver();

    FinalSummaryWebSearchEnhancer(ToolRegistry toolRegistry,
                                  ToolRuntimeService toolRuntimeService,
                                  ObjectMapper objectMapper,
                                  AgentRuntimeProperties properties) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
    }

    Enhancement enhance(ChatModel chatModel,
                        String query,
                        String systemPrompt,
                        String candidateAnswer,
                        List<String> observations,
                        List<InteractionToolTrace> traces,
                        Map<String, Object> metadata) {
        if (!properties.isFinalSummaryWebSearchEnabled() || chatModel == null
            || candidateAnswer == null || candidateAnswer.isBlank()
            || toolRegistry == null || toolRuntimeService == null) {
            return Enhancement.skipped(observations, traces);
        }
        String toolName = resolveInternalWebSearchTool();
        if (toolName == null || toolName.isBlank()) {
            record(metadata, "finalSummaryWebSearchSkippedReason", "web_search_unavailable");
            return Enhancement.skipped(observations, traces);
        }

        SearchDecision decision = decide(
            chatModel, query, systemPrompt, candidateAnswer, observations, traces, metadata);
        record(metadata, "finalSummaryWebSearchDecision", decision.needed());
        record(metadata, "finalSummaryWebSearchDecisionReason", decision.reason());
        record(metadata, "finalSummaryWebSearchKeywords", decision.keywords());
        if (!decision.needed() || decision.keywords().isEmpty()) {
            record(metadata, "finalSummaryWebSearchSkippedReason", "existing_evidence_sufficient");
            log.info("Final summary web search skipped runId={} reason={} successfulWebTrace={}",
                safe(text(metadata, "agentRunId", "__agentRunId")), safe(decision.reason()),
                hasSuccessfulWebTrace(traces));
            return Enhancement.skipped(observations, traces);
        }
        log.info(
            "准备联网检索 stage={} runId={} tool={} keywords={} reason={}",
            CANDIDATE_STAGE,
            safe(text(metadata, "agentRunId", "__agentRunId")),
            toolName,
            decision.keywords(),
            safe(decision.reason())
        );
        List<InteractionToolTrace> augmentedTraces = new ArrayList<>(traces == null ? List.of() : traces);
        List<SearchEvidence> evidence = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String keyword : decision.keywords().stream()
            .limit(properties.finalSummaryWebSearchMaxKeywords()).toList()) {
            ToolRuntimeExecution execution = execute(toolName, keyword, metadata);
            if (execution != null && execution.trace() != null) augmentedTraces.add(execution.trace());
            ToolOutput output = execution == null ? null : execution.output();
            if (output == null || !output.isSuccess()) {
                failures.add(keyword + ": " + safe(output == null ? null : output.getErrorMessage()));
                continue;
            }
            if (!containsTencentWsaEvidence(output.getData())) {
                failures.add(keyword + ": external web provider unavailable");
                continue;
            }
            evidence.add(new SearchEvidence(keyword, output.getData()));
        }
        record(metadata, "finalSummaryWebSearchAttempted", true);
        record(metadata, "finalSummaryWebSearchFailureDetails", failures);
        if (evidence.isEmpty()) {
            record(metadata, "finalSummaryWebSearchUsed", false);
            return new Enhancement(null, copy(observations), List.copyOf(augmentedTraces), true, false);
        }

        String evidenceText = formatEvidence(evidence);
        String enhancedAnswer = synthesize(
            chatModel, query, systemPrompt, candidateAnswer, observations, evidenceText);
        if (enhancedAnswer == null || enhancedAnswer.isBlank()) {
            record(metadata, "finalSummaryWebSearchUsed", false);
            record(metadata, "finalSummaryWebSearchFailure", "empty_enhanced_answer");
            return new Enhancement(null, copy(observations), List.copyOf(augmentedTraces), true, false);
        }
        List<String> augmentedObservations = new ArrayList<>(copy(observations));
        augmentedObservations.add(evidenceText);
        record(metadata, "finalSummaryWebSearchUsed", true);
        record(metadata, "finalSummaryWebSearchEvidenceCount", evidence.size());
        record(metadata, "finalSummaryWebSearchEnhancedAnswerPreview", preview(enhancedAnswer, 1_000));
        return new Enhancement(
            enhancedAnswer.trim(),
            List.copyOf(augmentedObservations),
            List.copyOf(augmentedTraces),
            true,
            true
        );
    }

    private SearchDecision decide(ChatModel model,
                                  String query,
                                  String systemPrompt,
                                  String answer,
                                  List<String> observations,
                                  List<InteractionToolTrace> traces,
                                  Map<String, Object> metadata) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You decide whether a final answer needs one internal web retrieval round before delivery.\n");
        prompt.append("Request retrieval only when it can materially improve correctness: current events or changing facts, "
            + "place-name/entity disambiguation, an important fact missing from internal observations, or unresolved conflicting evidence.\n");
        prompt.append("Do not request it for writing, translation, coding based on supplied context, stable general knowledge, "
            + "or when existing evidence already fully supports the answer.\n");
        prompt.append("This decision is not evidence. Return strict JSON only.\n");
        if (metadata != null && metadata.get("answerContract") != null) {
            prompt.append("Answer Contract:\n").append(metadata.get("answerContract")).append("\n\n");
        }
        if (metadata != null && metadata.get("evidenceSufficiencyGate") != null) {
            prompt.append("Deterministic evidence sufficiency gate:\n")
                .append(metadata.get("evidenceSufficiencyGate"))
                .append("\nUse this gap signal, but request web retrieval only when external retrieval is appropriate for the request.\n\n");
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(preview(systemPrompt, 2_000)).append("\n\n");
        }
        prompt.append("User request:\n").append(safe(query)).append("\n\n");
        prompt.append("Candidate answer:\n").append(preview(answer, 6_000)).append("\n\n");
        prompt.append("Observation summary:\n").append(preview(String.join("\n",
            observations == null ? List.of() : observations), 8_000)).append("\n\n");
        prompt.append("A successful web_search trace already exists: ")
            .append(hasSuccessfulWebTrace(traces)).append("\n");
        prompt.append("JSON schema: {\"needed\":true|false,"
            + "\"keywords\":[\"concise search phrase\"],"
            + "\"reason\":\"brief material evidence gap\"}\n");
        String raw = model.chat(prompt.toString());
        log.info("agentModelRawOutput phase=final_summary_web_decision raw=\n{}",
            ModelProtocolJson.prettyJsonForLog(raw));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(extractJson(raw), Map.class);
            boolean needed = Boolean.TRUE.equals(payload.get("needed"))
                || Boolean.parseBoolean(String.valueOf(payload.get("needed")));
            List<String> keywords = stringList(payload.get("keywords")).stream()
                .map(String::trim).filter(value -> !value.isBlank()).distinct()
                .limit(properties.finalSummaryWebSearchMaxKeywords()).toList();
            return new SearchDecision(needed && !keywords.isEmpty(), keywords,
                safe(payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
        } catch (Exception ex) {
            log.warn("Final summary web-search decision could not be parsed: {}", ex.getMessage());
            return new SearchDecision(false, List.of(), "decision_parse_failed");
        }
    }

    private ToolRuntimeExecution execute(String toolName, String keyword, Map<String, Object> metadata) {
        String runId = text(metadata, "agentRunId", "__agentRunId");
        String requestId = firstNonBlank(text(metadata, "requestId"), runId);
        String conversationId = text(metadata, "conversationId");
        String tenantId = text(metadata, "tenantId");
        String userId = text(metadata, "userId");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("internalPurpose", CANDIDATE_STAGE);
        context.put("userFacingTool", false);
        putIfText(context, "tenantId", tenantId);
        putIfText(context, "userId", userId);
        ToolInput input = ToolInput.builder()
            .requestId(requestId)
            .conversationId(conversationId)
            .userId(userId)
            .parameters(Map.of(
                "query", keyword,
                "num_results", properties.finalSummaryWebSearchResultLimit()))
            .context(context)
            .build();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("internalFinalSummaryEnhancement", true);
        attributes.put("maxAutomaticCalls", properties.finalSummaryWebSearchMaxKeywords());
        if (metadata != null && metadata.get("requiredToolParameters") instanceof Map<?, ?> required) {
            attributes.put("requiredToolParameters", required);
        }
        putIfText(attributes, "agentRunId", runId);
        return toolRuntimeService.execute(ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId(requestId)
            .conversationId(conversationId)
            .tenantId(tenantId)
            .userId(userId)
            .allowedTools(List.of(toolName))
            .toolInput(input)
            .attributes(attributes)
            .build());
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (target != null && value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String synthesize(ChatModel model,
                              String query,
                              String systemPrompt,
                              String originalAnswer,
                              List<String> observations,
                              String webEvidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Produce an improved final-answer candidate using the original candidate and newly retrieved web evidence.\n");
        prompt.append("Use web evidence only for claims it directly supports. Preserve stronger internal/tool evidence and explicitly resolve conflicts.\n");
        prompt.append("Do not mention this internal enhancement process. Keep readable Markdown and include the source URL near web-derived claims.\n");
        prompt.append("If the web evidence does not materially improve the answer, reproduce the original answer exactly.\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("\nSystem instruction:\n").append(preview(systemPrompt, 2_000)).append("\n");
        }
        prompt.append("\nUser request:\n").append(safe(query)).append("\n");
        prompt.append("\nOriginal candidate:\n").append(originalAnswer).append("\n");
        prompt.append("\nExisting observations:\n").append(preview(String.join("\n",
            observations == null ? List.of() : observations), 8_000)).append("\n");
        prompt.append("\nNew web evidence:\n").append(webEvidence).append("\n");
        prompt.append("\nReturn only the user-facing answer, without JSON or analysis notes.");
        String answer = model.chat(prompt.toString());
        log.info("agentModelOutput phase=final_summary_web_synthesis answer=\n{}",
            ModelProtocolJson.prettyJsonForLog(answer));
        return answer;
    }

    private String formatEvidence(List<SearchEvidence> batches) {
        StringBuilder text = new StringBuilder(
            "Final-summary web enhancement evidence (Tencent WSA via internal web_search):\n");
        int label = 1;
        for (SearchEvidence batch : batches) {
            text.append("Search query: ").append(batch.keyword()).append('\n');
            for (Map<String, Object> result : resultMaps(batch.data())) {
                String url = value(result, "url", "sourceUrl");
                String title = value(result, "title");
                String snippet = value(result, "snippet", "summary", "content");
                if (url.isBlank() && title.isBlank()) continue;
                text.append("[网页").append(label++).append("] ")
                    .append(title.isBlank() ? url : title).append('\n');
                if (!url.isBlank()) text.append("URL: ").append(url).append('\n');
                if (!snippet.isBlank()) text.append("Evidence: ").append(preview(snippet, 800)).append('\n');
            }
        }
        return preview(text.toString(), properties.finalSummaryWebSearchEvidenceMaxChars());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultMaps(Object data) {
        if (!(data instanceof Map<?, ?> root)) return List.of();
        Object results = root.get("results");
        if (!(results instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                maps.add((Map<String, Object>) map);
            }
        }
        return maps;
    }

    private boolean containsTencentWsaEvidence(Object value) {
        if (value == null) return false;
        try {
            String json = objectMapper.writeValueAsString(value).toLowerCase(Locale.ROOT);
            return json.contains("tencent_wsa") || json.contains("tencent-wsa")
                || json.contains("external_web_search") || json.contains("hybrid_news_and_web");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasSuccessfulWebTrace(List<InteractionToolTrace> traces) {
        return traces != null && traces.stream().anyMatch(trace -> trace != null && trace.isSuccess()
            && toolNames.isWebEvidenceToolName(trace.getToolName()));
    }

    private String resolveInternalWebSearchTool() {
        Set<String> names = toolRegistry.getAllToolNames();
        if (names == null || names.isEmpty()) return null;
        if (names.contains("web_search")) {
            return "web_search";
        }
        return names.stream()
            .filter(toolNames::isWebSearchToolName)
            .findFirst()
            .orElse(null);
    }

    private String value(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private String text(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (value instanceof Iterable<?> values) {
            Set<String> result = new LinkedHashSet<>();
            values.forEach(item -> {
                if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
            });
            return List.copyOf(result);
        }
        if (value instanceof String text && !text.isBlank()) return List.of(text);
        return List.of();
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        int first = raw.indexOf('{');
        int last = raw.lastIndexOf('}');
        return first >= 0 && last > first ? raw.substring(first, last + 1) : raw;
    }

    private List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private void record(Map<String, Object> metadata, String key, Object value) {
        if (metadata != null && value != null) metadata.put(key, value);
    }

    private String preview(String value, int maxChars) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    record Enhancement(
        String enhancedAnswer,
        List<String> observations,
        List<InteractionToolTrace> traces,
        boolean attempted,
        boolean used
    ) {
        static Enhancement skipped(List<String> observations, List<InteractionToolTrace> traces) {
            return new Enhancement(null,
                observations == null ? List.of() : List.copyOf(observations),
                traces == null ? List.of() : List.copyOf(traces),
                false, false);
        }
    }

    private record SearchDecision(boolean needed, List<String> keywords, String reason) { }
    private record SearchEvidence(String keyword, Object data) { }
}
