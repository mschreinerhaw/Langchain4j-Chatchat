package com.chatchat.agents.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LangChain4j adapter for DashScope's native Generation and MultiModalConversation
 * HTTP endpoints. These endpoints use input/parameters envelopes and cannot be
 * called by the OpenAI client merely by changing its base URL.
 */
public final class DashScopeNativeChatModel implements ChatModel {

    private final String endpoint;
    private final boolean multimodal;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final int configuredMaxTokens;
    private final int maxRetries;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public DashScopeNativeChatModel(String endpoint,
                                    boolean multimodal,
                                    String apiKey,
                                    String modelName,
                                    Duration timeout,
                                    int configuredMaxTokens,
                                    int maxRetries,
                                    HttpClient httpClient,
                                    ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.multimodal = multimodal;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
        this.configuredMaxTokens = configuredMaxTokens;
        this.maxRetries = Math.max(0, maxRetries);
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DashScope API key is not configured");
        }
        try {
            String requestBody = mapper.writeValueAsString(requestBody(chatRequest));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                request.timeout(timeout);
            }
            HttpResponse<String> response = sendWithRetry(request.build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DashScope HTTP " + response.statusCode() + ": "
                    + errorMessage(response.body()));
            }
            return response(mapper.readTree(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("DashScope request failed: " + exception.getMessage(), exception);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 500 || attempt == maxRetries) {
                    return response;
                }
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt == maxRetries) {
                    throw exception;
                }
            }
        }
        if (response != null) {
            return response;
        }
        throw lastFailure == null ? new IOException("DashScope request failed") : lastFailure;
    }

    ObjectNode requestBody(ChatRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", firstNonBlank(request.modelName(), modelName));
        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        for (ChatMessage message : request.messages()) {
            messages.add(message(message));
        }
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("result_format", "message");
        put(parameters, "temperature", request.temperature());
        put(parameters, "top_p", request.topP());
        put(parameters, "top_k", request.topK());
        put(parameters, "frequency_penalty", request.frequencyPenalty());
        put(parameters, "presence_penalty", request.presencePenalty());
        int maxTokens = request.maxOutputTokens() == null ? configuredMaxTokens : request.maxOutputTokens();
        if (maxTokens > 0) {
            parameters.put("max_tokens", maxTokens);
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            parameters.set("stop", mapper.valueToTree(request.stopSequences()));
        }
        if (request.responseFormat() != null && request.responseFormat().type() != ResponseFormatType.TEXT) {
            ObjectNode format = parameters.putObject("response_format");
            format.put("type", request.responseFormat().type() == ResponseFormatType.JSON
                ? "json_object" : "json_schema");
        }
        addTools(parameters, request.toolSpecifications());
        if (request.toolChoice() != null) {
            parameters.put("tool_choice", request.toolChoice().name().toLowerCase(Locale.ROOT));
        }
        return root;
    }

    private ObjectNode message(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        if (message instanceof SystemMessage system) {
            node.put("role", "system");
            putTextContent(node, system.text());
        } else if (message instanceof UserMessage user) {
            node.put("role", "user");
            if (multimodal) {
                ArrayNode content = node.putArray("content");
                user.contents().forEach(item -> content.add(content(item)));
            } else {
                node.put("content", user.singleText());
            }
        } else if (message instanceof AiMessage ai) {
            node.put("role", "assistant");
            putTextContent(node, ai.text() == null ? "" : ai.text());
            if (ai.hasToolExecutionRequests()) {
                ArrayNode calls = node.putArray("tool_calls");
                ai.toolExecutionRequests().forEach(call -> calls.add(toolCall(call)));
            }
        } else if (message instanceof ToolExecutionResultMessage tool) {
            node.put("role", "tool");
            node.put("tool_call_id", tool.id());
            node.put("name", tool.toolName());
            putTextContent(node, tool.text());
        } else {
            throw new IllegalArgumentException("Unsupported DashScope message type: " + message.type());
        }
        return node;
    }

    private void putTextContent(ObjectNode message, String text) {
        if (multimodal) {
            message.putArray("content").addObject().put("text", text == null ? "" : text);
        } else {
            message.put("content", text == null ? "" : text);
        }
    }

    private ObjectNode content(dev.langchain4j.data.message.Content content) {
        ObjectNode node = mapper.createObjectNode();
        if (content instanceof TextContent text) {
            node.put("text", text.text());
        } else if (content instanceof ImageContent image) {
            node.put("image", mediaValue(image.image().url(), image.image().base64Data(), image.image().mimeType()));
        } else if (content instanceof AudioContent audio) {
            node.put("audio", mediaValue(audio.audio().url(), audio.audio().base64Data(), audio.audio().mimeType()));
        } else {
            throw new IllegalArgumentException("Unsupported DashScope content type: " + content.type());
        }
        return node;
    }

    private String mediaValue(URI url, String base64, String mimeType) {
        if (url != null) {
            return url.toString();
        }
        if (base64 != null && !base64.isBlank()) {
            return "data:" + firstNonBlank(mimeType, "application/octet-stream") + ";base64," + base64;
        }
        throw new IllegalArgumentException("DashScope media content has neither URL nor base64 data");
    }

    private ObjectNode toolCall(ToolExecutionRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", request.id());
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", request.name());
        function.put("arguments", request.arguments());
        return node;
    }

    private void addTools(ObjectNode parameters, List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return;
        }
        ArrayNode tools = parameters.putArray("tools");
        for (ToolSpecification specification : specifications) {
            JsonNode serialized;
            try {
                serialized = mapper.readTree(specification.toJson());
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid tool specification " + specification.name(), exception);
            }
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", specification.name());
            function.put("description", specification.description() == null ? "" : specification.description());
            function.set("parameters", serialized.path("parameters").isMissingNode()
                ? mapper.createObjectNode() : serialized.path("parameters"));
        }
    }

    private ChatResponse response(JsonNode root) {
        if (root.hasNonNull("code") && !"200".equals(root.path("code").asText())) {
            throw new IllegalStateException("DashScope " + root.path("code").asText() + ": "
                + root.path("message").asText("Request failed"));
        }
        JsonNode choice = root.path("output").path("choices").path(0);
        JsonNode message = choice.path("message");
        String text = responseText(message.path("content"));
        List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            toolCalls.add(ToolExecutionRequest.builder()
                .id(call.path("id").asText())
                .name(function.path("name").asText())
                .arguments(function.path("arguments").asText("{}"))
                .build());
        }
        AiMessage aiMessage = AiMessage.builder()
            .text(text)
            .thinking(message.path("reasoning_content").isTextual()
                ? message.path("reasoning_content").asText() : null)
            .toolExecutionRequests(toolCalls)
            .build();
        JsonNode usage = root.path("usage");
        TokenUsage tokenUsage = new TokenUsage(
            integer(usage, "input_tokens"), integer(usage, "output_tokens"), integer(usage, "total_tokens"));
        return ChatResponse.builder()
            .id(root.path("request_id").asText(null))
            .modelName(modelName)
            .aiMessage(aiMessage)
            .tokenUsage(tokenUsage)
            .finishReason(finishReason(choice.path("finish_reason").asText(null), !toolCalls.isEmpty()))
            .build();
    }

    private String responseText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                if (item.path("text").isTextual()) {
                    text.append(item.path("text").asText());
                }
            }
        }
        return text.toString();
    }

    private FinishReason finishReason(String reason, boolean hasTools) {
        if (hasTools || "tool_calls".equalsIgnoreCase(reason)) {
            return FinishReason.TOOL_EXECUTION;
        }
        if ("length".equalsIgnoreCase(reason)) {
            return FinishReason.LENGTH;
        }
        if ("stop".equalsIgnoreCase(reason)) {
            return FinishReason.STOP;
        }
        return FinishReason.OTHER;
    }

    private Integer integer(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).intValue() : null;
    }

    private String errorMessage(String body) {
        try {
            JsonNode error = mapper.readTree(body);
            return firstNonBlank(error.path("message").asText(null), body);
        } catch (IOException ignored) {
            return body;
        }
    }

    private void put(ObjectNode node, String field, Number value) {
        if (value != null) {
            node.put(field, value.doubleValue());
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
