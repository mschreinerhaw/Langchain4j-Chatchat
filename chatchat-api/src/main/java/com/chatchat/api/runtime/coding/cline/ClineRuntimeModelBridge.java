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
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Converts the portable Cline model turn to the already configured server-side model. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
public final class ClineRuntimeModelBridge {
    private static final int MAX_MESSAGES = 64, MAX_TOOLS = 4, MAX_TEXT = 256_000;
    private final ModelResourceRegistry resources;
    private final ConfigurableChatModelFactory models;
    private final ObjectMapper json;
    private CodingParentModels parentModels;
    private CodingWorkspaceService workspace;
    private int configuredMaxOutputTokens = 8192;
    private int workspaceMaxOutputTokens=8192;
    private int maxModelResponseRepairs = 2;
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.cline.max-model-response-repairs:2}")
    public void setMaxModelResponseRepairs(int attempts) {
        if (attempts < 0 || attempts > 8) throw new IllegalArgumentException("Invalid model response repair limit");
        maxModelResponseRepairs = attempts;
    }
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.workspace.max-output-tokens:8192}")
    public void setWorkspaceMaxOutputTokens(int tokens){
        if(tokens<1 || tokens>32768)throw new IllegalArgumentException("Invalid workspace reply token limit");
        workspaceMaxOutputTokens=tokens;
    }
    @org.springframework.beans.factory.annotation.Value("${chatchat.coding.cline.max-output-tokens:8192}")
    public void setMaxOutputTokens(int tokens) {
        if (tokens < 1 || tokens > 262144) throw new IllegalArgumentException("Invalid Coding model output limit");
        configuredMaxOutputTokens = tokens;
    }

    public int maxOutputTokens(CodingTaskContract contract) {
        int configured=CodingWorkspaceService.supports(contract)?Math.min(configuredMaxOutputTokens,workspaceMaxOutputTokens):configuredMaxOutputTokens;
        return (int)Math.max(1, Math.min(configured, contract.budget().maxModelTokens() / 2));
    }

    public long modelTokenReservation(CodingTaskContract contract) {
        // Reserve room for both the reply and its prompt under the durable tool budget.
        return Math.min(contract.budget().maxModelTokens(), 2L * maxOutputTokens(contract));
    }
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
        Map<?,?> effectiveModel = contract.runtimeContext().get("model") instanceof Map<?,?> snapshot ? snapshot : Map.of();
        if (effectiveModel.get("selectedModel") instanceof String selected && !selected.isBlank()) modelName = selected;
        Map<?,?> executionProfile=contract.runtimeContext().get("executionProfile") instanceof Map<?,?> profile ? profile : Map.of();
        if(executionProfile.get("selectedModel") instanceof String selected && !selected.isBlank())modelName=selected;
        if(CodingWorkspaceService.supports(contract)) {
            if(workspace==null)throw new SecurityException("Workspace Coding profile is not enabled");
            String requested=(String)workspace.requireAuthorized(contract).get("modelName");
            if(requested!=null && !requested.isBlank())modelName=requested;
        }
        if (modelName == null || modelName.isBlank()) throw new IllegalStateException("Default server chat model is not configured");
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
            throw new IllegalStateException("Authorized tool model binding is unavailable; reconcile before dispatch");
        if (parent == null) resources.require(modelName);
        int outputLimit = maxOutputTokens(contract);
        dev.langchain4j.model.chat.request.ChatRequestParameters requestParameters =
            executionProfile.get("reasoningEffort") instanceof String effort
                ? dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                    .reasoningEffort(effort).maxOutputTokens(outputLimit).build()
                : dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .maxOutputTokens(outputLimit).build();
        Instant started = Instant.now();
        ChatResponse answer = (parent == null ? models.create(modelName, Duration.ofSeconds(90), outputLimit) : parent)
            .chat(ChatRequest.builder().messages(messages).toolSpecifications(tools)
                .parameters(requestParameters).build());
        if (answer == null || answer.aiMessage() == null) throw new IllegalStateException("Model returned no assistant message");
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        String responseError = null;
        String responseDiagnostic = null;
        for (ToolExecutionRequest call : answer.aiMessage().toolExecutionRequests()) {
            try {
                allowedTool(contract, call.name());
                if (call.id() == null || call.id().isBlank() || call.id().length() > 256)
                    throw new IllegalArgumentException("Tool call identity is invalid");
                if (call.arguments() == null || call.arguments().length() > MAX_TEXT)
                    throw new IllegalArgumentException("Tool arguments exceed limit");
                JsonNode input = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(call.arguments());
                if (input == null || !input.isObject()) throw new IllegalArgumentException("Tool input must be an object");
                toolCalls.add(Map.of("id", call.id(), "name", call.name(), "input", input));
            } catch (Exception invalid) {
                // The provider has returned. Preserve its stop/usage receipt even when its
                // tool output is unusable; this is not an unknown external execution.
                responseError = "Model returned invalid or unauthorized tool arguments";
                responseDiagnostic = invalid instanceof SecurityException ? "MODEL_TOOL_NOT_AUTHORIZED"
                    : invalid instanceof com.fasterxml.jackson.core.JsonProcessingException
                        && (answer.finishReason() != null && "LENGTH".equals(answer.finishReason().name())
                            || answer.tokenUsage() != null && answer.tokenUsage().outputTokenCount() != null
                                && answer.tokenUsage().outputTokenCount() >= outputLimit)
                        ? "MODEL_OUTPUT_TRUNCATED" : "MODEL_TOOL_ARGUMENTS_INVALID";
                toolCalls.clear();
                break;
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        if (answer.tokenUsage() != null) {
            usage.put("inputTokens", answer.tokenUsage().inputTokenCount());
            usage.put("outputTokens", answer.tokenUsage().outputTokenCount());
            var details=com.chatchat.agents.orchestration.model.ModelUsageMetrics.observed(answer.tokenUsage());
            for(String key:List.of("cacheReadTokens","cacheWriteTokens","reasoningTokens"))
                if(details.containsKey(key))usage.put(key,details.get(key));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", answer.aiMessage().text() == null ? "" : answer.aiMessage().text());
        result.put("toolCalls", toolCalls);
        result.put("usage", usage);
        result.put("maxOutputTokens", outputLimit);
        var modelInfo = new LinkedHashMap<String,Object>();
        if (parent == null || effectiveModel.containsKey("selectedModel")) modelInfo.put("selectedModel", modelName);
        for (String key : List.of("providerModel", "protocol")) {
            Object identity=executionProfile.containsKey("selectedModel") ? executionProfile.get(key) : effectiveModel.get(key);
            if(identity instanceof String value) modelInfo.put(key,value);
        }
        if (answer.modelName() != null && !answer.modelName().isBlank())
            modelInfo.put("providerModel", answer.modelName());
        modelInfo.put("startedAt", started.toString());
        modelInfo.put("finishedAt", Instant.now().toString());
        modelInfo.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        modelInfo.put("maxOutputTokens", outputLimit);
        if(executionProfile.get("complexity") instanceof String complexity)modelInfo.put("complexity",complexity);
        if(executionProfile.get("reasoningEffort") instanceof String effort)modelInfo.put("reasoningEffort",effort);
        result.put("modelInfo", modelInfo);
        if (answer.finishReason() != null) result.put("finishReason", answer.finishReason().name());
        if (answer.id() != null) result.put("requestId", answer.id());
        if (responseError != null) {
            result.put("error", responseError);
            boolean usageKnown = answer.tokenUsage() != null && answer.tokenUsage().inputTokenCount() != null
                && answer.tokenUsage().outputTokenCount() != null && answer.tokenUsage().inputTokenCount() >= 0
                && answer.tokenUsage().outputTokenCount() >= 0;
            result.put("modelResponseFeedback", Map.of("schemaVersion", "runtime_os.coding.model_feedback.v1",
                "diagnosticCode", responseDiagnostic, "repairAllowed", usageKnown && maxModelResponseRepairs > 0
                    && !"MODEL_TOOL_NOT_AUTHORIZED".equals(responseDiagnostic),
                "maxRepairAttempts", maxModelResponseRepairs, "maxOutputTokens", outputLimit));
            throw new InvalidModelResponseException(result);
        }
        return result;
    }

    public static final class InvalidModelResponseException extends IllegalStateException {
        private final Map<String, Object> receipt;
        InvalidModelResponseException(Map<String, Object> receipt) {
            super(String.valueOf(receipt.get("error")));
            this.receipt = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(receipt));
        }
        public Map<String, Object> receipt() { return receipt; }
    }

    private static String allowedTool(CodingTaskContract contract, String name) {
        Set<String> profile = CodingWorkspaceService.supports(contract)
            ? Set.of("workspace_read", "workspace_propose") : com.chatchat.common.runtime.coding.CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
            ? Set.of("python_execute", "artifact_read", "data_request", "sql_validate") : Set.of("python_execute", "metadata_read", "artifact_read");
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
