package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.orchestration.model.AgentBudgetExceededException;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/**
 * Uses the model provider's native function-calling protocol to generate arguments
 * for one tool already selected by the Runtime.
 *
 * <p>This component never executes a tool. It only converts a provider tool call into
 * an {@link AgentDecision}; the existing Agent Runtime remains the sole execution and
 * governance boundary.</p>
 */
@Slf4j
public final class NativeToolCallingPlanner {

    private static final Pattern PORTABLE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String TOOL = "tool";

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public NativeToolCallingPlanner(ToolRegistry toolRegistry,
                                    ObjectMapper objectMapper,
                                    boolean enabled) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public Optional<AgentDecision> decide(ChatModel model,
                                          String query,
                                          String systemPrompt,
                                          String designatedTool,
                                          List<String> observations,
                                          RuntimeDesignatedFunctionCall designation) {
        if (!enabled || model == null || toolRegistry == null || objectMapper == null) {
            return Optional.empty();
        }
        if (designation == null || !designation.required()
            || designatedTool == null || !designatedTool.equals(designation.toolName())) {
            return Optional.empty();
        }
        Optional<String> registeredTool = portableRegisteredTool(designatedTool);
        if (registeredTool.isEmpty()) {
            return Optional.empty();
        }
        String toolName = registeredTool.get();
        Optional<ToolSpecification> specification = toSpecification(toolName);
        if (specification.isEmpty()) return Optional.empty();

        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(
                    SystemMessage.from(nativeSystemPrompt(systemPrompt)),
                    UserMessage.from(nativeUserPrompt(query, observations))
                ))
                // Exactly one Runtime-designated tool is published. The model never receives a choice set.
                .toolSpecifications(List.of(specification.get()))
                .toolChoice(ToolChoice.REQUIRED)
                .build());
            AiMessage message = response == null ? null : response.aiMessage();
            if (message == null || !message.hasToolExecutionRequests()) {
                return Optional.empty();
            }
            List<ToolExecutionRequest> calls = message.toolExecutionRequests();
            if (calls == null || calls.size() != 1) {
                log.info("Native function-calling planner deferred because provider returned {} calls",
                    calls == null ? 0 : calls.size());
                return Optional.empty();
            }
            ToolExecutionRequest call = calls.get(0);
            if (call == null || !toolName.equals(call.name())) {
                log.warn("Native function-calling planner rejected non-designated tool expected={} actual={}",
                    toolName, call == null ? null : call.name());
                return Optional.empty();
            }
            Map<String, Object> arguments = parseArguments(call.arguments());
            if (arguments == null) {
                return Optional.empty();
            }
            Map<String, Object> executionPlan = new LinkedHashMap<>();
            executionPlan.put("decisionProtocol", "native_function_calling");
            executionPlan.put("runtimeDesignationContract", designation.contractVersion());
            executionPlan.put("runtimeDesignationSource", designation.source());
            if (call.id() != null && !call.id().isBlank()) {
                executionPlan.put("nativeToolCallId", call.id());
            }
            executionPlan.put("nativeToolCallGoverned", true);
            return Optional.of(new AgentDecision(
                TOOL,
                call.name(),
                arguments,
                null,
                "native_function_calling",
                Map.copyOf(executionPlan),
                false
            ));
        } catch (AgentBudgetExceededException failure) {
            throw failure;
        } catch (CancellationException failure) {
            throw failure;
        } catch (Exception failure) {
            log.info("Native function-calling planner unavailable; falling back to JSON planner: {}",
                failure.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ToolSpecification> toSpecification(String toolName) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null || !metadata.isAgentCompatible()) {
            return Optional.empty();
        }
        ObjectNode specification = objectMapper.createObjectNode();
        specification.put("name", toolName);
        specification.put("description", firstNonBlank(metadata.getDescription(), metadata.getTitle(), toolName));
        specification.set("parameters", objectMapper.valueToTree(parameterSchema(metadata.getParameters())));
        try {
            return Optional.of(ToolSpecification.fromJson(objectMapper.writeValueAsString(specification)));
        } catch (Exception invalidSpecification) {
            log.warn("Cannot publish native function specification for tool={}: {}",
                toolName, invalidSpecification.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> parameterSchema(List<ToolParameter> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (parameters != null) {
            for (ToolParameter parameter : parameters) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                Map<String, Object> property = new LinkedHashMap<>(
                    parameter.getMetadata() == null ? Map.of() : parameter.getMetadata());
                property.putIfAbsent("type", firstNonBlank(parameter.getType(), "object"));
                putIfPresent(property, "description", parameter.getDescription());
                putIfPresent(property, "default", parameter.getDefaultValue());
                putIfPresent(property, "minLength", parameter.getMinLength());
                putIfPresent(property, "maxLength", parameter.getMaxLength());
                putIfPresent(property, "minimum", parameter.getMinimum());
                putIfPresent(property, "maximum", parameter.getMaximum());
                putIfPresent(property, "exclusiveMinimum", parameter.getExclusiveMinimum());
                putIfPresent(property, "exclusiveMaximum", parameter.getExclusiveMaximum());
                putIfPresent(property, "pattern", parameter.getPattern());
                if (parameter.getEnumValues() != null && parameter.getEnumValues().length > 0) {
                    property.put("enum", List.of(parameter.getEnumValues()));
                }
                properties.put(parameter.getName(), property);
                if (parameter.isRequired()) {
                    required.add(parameter.getName());
                }
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private Map<String, Object> parseArguments(String rawArguments) {
        try {
            if (rawArguments == null || rawArguments.isBlank()) {
                return Map.of();
            }
            Map<String, Object> arguments = objectMapper.readValue(rawArguments, new TypeReference<>() {});
            return arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        } catch (Exception invalidArguments) {
            log.warn("Native function-calling planner returned invalid arguments: {}", invalidArguments.getMessage());
            return null;
        }
    }

    private Optional<String> portableRegisteredTool(String candidate) {
        if (candidate == null || !PORTABLE_TOOL_NAME.matcher(candidate).matches()
            || !toolRegistry.hasTool(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private String nativeSystemPrompt(String systemPrompt) {
        return firstNonBlank(systemPrompt, "You generate arguments for a Runtime-designated tool.")
            + "\n\nThe Runtime has already selected the only supplied function. Generate exactly one call "
            + "to that function. Do not select, replace, skip, or invent a tool. The Runtime validates "
            + "all arguments and remains the sole execution authority.";
    }

    private String nativeUserPrompt(String query, List<String> observations) {
        StringBuilder prompt = new StringBuilder(firstNonBlank(query, ""));
        if (observations != null && !observations.isEmpty()) {
            prompt.append("\n\nTrusted runtime observations:\n");
            observations.forEach(observation -> prompt.append("- ").append(observation).append('\n'));
        }
        return prompt.toString();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.putIfAbsent(key, value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
