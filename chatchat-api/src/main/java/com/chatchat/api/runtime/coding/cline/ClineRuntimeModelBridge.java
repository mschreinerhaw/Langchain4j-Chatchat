package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.api.runtime.coding.CodingParentModels;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Converts the portable Cline model turn to the already configured server-side model. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
public final class ClineRuntimeModelBridge {
    private static final int MAX_MESSAGES = 64, MAX_TOOLS = 3, MAX_TEXT = 256_000;
    private final ModelResourceRegistry resources;
    private final ConfigurableChatModelFactory models;
    private final ObjectMapper json;
    private CodingParentModels parentModels;
    private CodingWorkspaceService workspace;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setWorkspace(CodingWorkspaceService workspace) { this.workspace=workspace; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setParentModels(CodingParentModels models) { this.parentModels = models; }

    public ClineRuntimeModelBridge(ModelResourceRegistry resources, ConfigurableChatModelFactory models, ObjectMapper json) {
        this.resources = resources; this.models = models; this.json = json;
    }

    public Map<String, Object> complete(CodingTaskContract contract, JsonNode request) {
        Objects.requireNonNull(contract);
        if (request == null || !request.isObject()) throw new IllegalArgumentException("Model request is required");
        String modelName = resources.defaultChatModel();
        if(CodingWorkspaceService.supports(contract)) {
            if(workspace==null)throw new SecurityException("Workspace Coding profile is not enabled");
            String requested=(String)workspace.requireAuthorized(contract).get("modelName");
            if(requested!=null && !requested.isBlank())modelName=requested;
        }
        if (modelName == null || modelName.isBlank()) throw new IllegalStateException("Default server chat model is not configured");
        resources.require(modelName);
        List<ChatMessage> messages = new ArrayList<>();
        String system = bounded(request.path("systemPrompt").asText(""), "system prompt");
        if (!system.isBlank()) messages.add(SystemMessage.from(system));
        JsonNode turns = request.path("messages");
        if (!turns.isArray() || turns.size() > MAX_MESSAGES) throw new IllegalArgumentException("Invalid model message count");
        for (JsonNode turn : turns) {
            String role = turn.path("role").asText();
            StringBuilder text = new StringBuilder();
            List<ToolExecutionRequest> calls = new ArrayList<>();
            JsonNode parts = turn.path("content");
            if (!parts.isArray()) throw new IllegalArgumentException("Model message content must be an array");
            for (JsonNode part : parts) {
                switch (part.path("type").asText()) {
                    case "text" -> text.append(bounded(part.path("text").asText(), "message text"));
                    case "tool-call" -> {
                        String name = allowedTool(contract, part.path("toolName").asText());
                        calls.add(ToolExecutionRequest.builder().id(bounded(part.path("toolCallId").asText(), "tool call ID"))
                            .name(name).arguments(part.path("input").toString()).build());
                    }
                    case "tool-result" -> {
                        if (!"tool".equals(role)) throw new IllegalArgumentException("Tool result has invalid role");
                        String name = allowedTool(contract, part.path("toolName").asText());
                        messages.add(ToolExecutionResultMessage.from(
                            bounded(part.path("toolCallId").asText(), "tool call ID"), name,
                            bounded(part.path("output").toString(), "tool result")));
                    }
                    default -> throw new IllegalArgumentException("Unsupported model message part");
                }
            }
            switch (role) {
                case "user" -> messages.add(UserMessage.from(bounded(text.toString(), "user message")));
                case "assistant" -> messages.add(AiMessage.builder().text(bounded(text.toString(), "assistant message"))
                    .toolExecutionRequests(calls).build());
                case "tool" -> { if (parts.isEmpty()) throw new IllegalArgumentException("Empty tool result"); }
                default -> throw new IllegalArgumentException("Unsupported model message role");
            }
        }
        JsonNode exposed = request.path("tools");
        if (!exposed.isArray() || exposed.size() > MAX_TOOLS) throw new IllegalArgumentException("Invalid model tool count");
        List<ToolSpecification> tools = new ArrayList<>();
        for (JsonNode tool : exposed) {
            String name = allowedTool(contract, tool.path("name").asText());
            if (!tool.path("inputSchema").isObject()) throw new IllegalArgumentException("Tool input schema is required");
            var spec = json.createObjectNode();
            spec.put("name", name);
            spec.put("description", bounded(tool.path("description").asText(""), "tool description"));
            spec.set("parameters", tool.path("inputSchema"));
            tools.add(ToolSpecification.fromJson(spec.toString()));
        }
        var parent = parentModels == null ? null : parentModels.find(contract.scope(), contract.taskId());
        if (parent == null && contract.metadataRefs().contains(CodingParentModels.REQUIRED_REF))
            throw new IllegalStateException("Parent metered model binding is unavailable; reconcile before dispatch");
        ChatResponse answer = (parent == null ? models.create(modelName, Duration.ofSeconds(90), 2048) : parent)
            .chat(ChatRequest.builder().messages(messages).toolSpecifications(tools)
                .parameters(dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder().maxOutputTokens(2048).build()).build());
        if (answer == null || answer.aiMessage() == null) throw new IllegalStateException("Model returned no assistant message");
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (ToolExecutionRequest call : answer.aiMessage().toolExecutionRequests()) {
            allowedTool(contract, call.name());
            Object input;
            try { input = json.readTree(call.arguments()); }
            catch (Exception invalid) { throw new IllegalStateException("Model returned invalid tool arguments", invalid); }
            toolCalls.add(Map.of("id", call.id(), "name", call.name(), "input", input));
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        if (answer.tokenUsage() != null) {
            usage.put("inputTokens", answer.tokenUsage().inputTokenCount());
            usage.put("outputTokens", answer.tokenUsage().outputTokenCount());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", answer.aiMessage().text() == null ? "" : answer.aiMessage().text());
        result.put("toolCalls", toolCalls);
        result.put("usage", usage);
        if (answer.id() != null) result.put("requestId", answer.id());
        return result;
    }

    private static String allowedTool(CodingTaskContract contract, String name) {
        Set<String> profile = CodingWorkspaceService.supports(contract)
            ? Set.of("workspace_read", "workspace_propose") : com.chatchat.common.runtime.coding.CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
            ? Set.of("python_execute", "data_request", "sql_validate") : Set.of("python_execute", "metadata_read", "artifact_read");
        if (!profile.contains(name)
                || !contract.authorization().allowedTools().contains(name))
            throw new SecurityException("Model tool is outside the Runtime authorization");
        return name;
    }
    private static String bounded(String value, String field) {
        if (value == null || value.length() > MAX_TEXT) throw new IllegalArgumentException(field + " exceeds limit");
        return value;
    }
}
