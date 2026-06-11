package com.chatchat.agents.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of ToolRegistry with support for both
 * simple and enhanced tools with rich metadata
 */
@Slf4j
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> simpleTools = new ConcurrentHashMap<>();
    private final Map<String, EnhancedTool> enhancedTools = new ConcurrentHashMap<>();
    private final Map<String, ToolMetadata> toolMetadata = new ConcurrentHashMap<>();

    @Override
    public void registerTool(String toolName, Tool tool) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }

        log.info("Registering simple tool: {}", toolName);
        simpleTools.put(toolName, tool);
    }

    @Override
    public void registerTool(String toolName, ToolMetadata metadata, EnhancedTool tool) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Tool metadata cannot be null");
        }
        if (tool == null) {
            throw new IllegalArgumentException("Enhanced tool cannot be null");
        }

        log.info("Registering enhanced tool: {} (v{})", toolName, metadata.getVersion());
        enhancedTools.put(toolName, tool);
        toolMetadata.put(toolName, metadata);
    }

    @Override
    public Tool getTool(String toolName) {
        return simpleTools.get(toolName);
    }

    @Override
    public EnhancedTool getEnhancedTool(String toolName) {
        return enhancedTools.get(toolName);
    }

    @Override
    public ToolMetadata getToolMetadata(String toolName) {
        return toolMetadata.get(toolName);
    }

    @Override
    public String executeTool(ToolExecutionRequest request) {
        log.debug("Executing tool (LangChain4j request): {}", request.name());

        Tool tool = getTool(request.name());
        if (tool == null) {
            EnhancedTool enhancedTool = getEnhancedTool(request.name());
            if (enhancedTool == null) {
                log.warn("Tool not found: {}", request.name());
                return "Tool not found: " + request.name();
            }
            // Execute enhanced tool and return string result
            try {
                ToolInput toolInput = ToolInput.builder()
                    .rawInput(request.arguments())
                    .build();
                ToolOutput output = enhancedTool.execute(toolInput);
                return output.getDataAsString();
            } catch (Exception e) {
                log.error("Error executing enhanced tool: {}", request.name(), e);
                return "Error executing tool: " + e.getMessage();
            }
        }

        try {
            long startTime = System.currentTimeMillis();
            String result = tool.execute(request.arguments());
            long executionTime = System.currentTimeMillis() - startTime;
            log.debug("Tool {} executed successfully in {}ms", request.name(), executionTime);
            return result;
        } catch (Exception e) {
            log.error("Error executing tool: {}", request.name(), e);
            return "Error executing tool: " + e.getMessage();
        }
    }

    @Override
    public ToolOutput executeEnhancedTool(String toolName, ToolInput toolInput) {
        log.info("Tool execution started tool={} requestId={} userId={} args={}",
            toolName,
            toolInput == null ? null : toolInput.getRequestId(),
            toolInput == null ? null : toolInput.getUserId(),
            ToolLogSummarizer.summarize(toolInput == null ? null : toolInput.getParameters()));

        EnhancedTool tool = getEnhancedTool(toolName);
        if (tool == null) {
            log.warn("Enhanced tool not found: {}", toolName);
            return ToolOutput.failure("Tool not found: " + toolName);
        }

        try {
            long startTime = System.currentTimeMillis();
            ToolOutput output = tool.execute(toolInput);
            long executionTime = System.currentTimeMillis() - startTime;
            output.setExecutionTimeMs(executionTime);
            if (output.isSuccess()) {
                log.info("Tool execution succeeded tool={} requestId={} durationMs={} result={}",
                    toolName,
                    toolInput == null ? null : toolInput.getRequestId(),
                    executionTime,
                    ToolLogSummarizer.summarize(output.getData()));
            } else {
                log.warn("Tool execution failed tool={} requestId={} durationMs={} error={} result={}",
                    toolName,
                    toolInput == null ? null : toolInput.getRequestId(),
                    executionTime,
                    output.getErrorMessage(),
                    ToolLogSummarizer.summarize(output.getData()));
            }
            return output;
        } catch (Exception e) {
            log.error("Error executing enhanced tool: {}", toolName, e);
            return ToolOutput.failure(e);
        }
    }

    @Override
    public List<Tool> getAllTools() {
        return new ArrayList<>(simpleTools.values());
    }

    @Override
    public List<EnhancedTool> getAllEnhancedTools() {
        return new ArrayList<>(enhancedTools.values());
    }

    @Override
    public boolean hasTool(String toolName) {
        return simpleTools.containsKey(toolName) || enhancedTools.containsKey(toolName);
    }

    @Override
    public Set<String> getAllToolNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(simpleTools.keySet());
        names.addAll(enhancedTools.keySet());
        return names;
    }

    @Override
    public void unregisterTool(String toolName) {
        log.info("Unregistering tool: {}", toolName);
        simpleTools.remove(toolName);
        enhancedTools.remove(toolName);
        toolMetadata.remove(toolName);
    }
}
