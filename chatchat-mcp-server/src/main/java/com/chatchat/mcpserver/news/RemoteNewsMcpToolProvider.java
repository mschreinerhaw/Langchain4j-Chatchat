package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private final NewsRuntimeClient client;
    private final Map<String, McpToolDefinition> definitions;

    public RemoteNewsMcpToolProvider(NewsRuntimeClient client) {
        this.client = client;
        this.definitions = List.of(
            definition("web_search", "资讯搜索", "从资讯正文及PDF/Excel附件分片中进行关键词与语义混合搜索。",
                List.of(text("query", "要搜索的资讯主题或关键词", true), number("num_results", "最大返回数量", 10, 1, 50)), true, 30),
            definition("news_search", "结构化资讯搜索", "按关键词、来源、发布时间和分类检索资讯正文及附件证据分片。",
                List.of(text("query", "搜索关键词或主题", true), array("sourceIds", "可选资讯源ID"),
                    text("startTime", "ISO-8601起始时间", false), text("endTime", "ISO-8601结束时间", false),
                    array("categories", "可选分类"), number("size", "最大返回数量", 10, 1, 50)), true, 30),
            definition("news_latest", "最新资讯", "查询资讯索引中的最新资讯。",
                List.of(array("sourceIds", "可选资讯源ID"), number("hours", "回看小时数", 24, 1, 720),
                    number("size", "最大返回数量", 10, 1, 50)), true, 20),
            definition("news_source_status", "资讯源状态", "只读查询资讯源配置状态和采集记录数。",
                List.of(text("sourceId", "可选资讯源ID", false)), false, 10)
        ).stream().collect(java.util.stream.Collectors.toMap(McpToolDefinition::name, item -> item));
    }

    @Override public String capabilityCode() { return McpCapabilityCodes.NEWS; }
    @Override public Collection<McpToolDefinition> definitions() { return definitions.values(); }
    @Override public Optional<McpToolExecutor> findExecutor(String toolName) {
        return definitions.containsKey(toolName) ? Optional.of(new RemoteExecutor(toolName)) : Optional.empty();
    }

    private class RemoteExecutor implements McpToolExecutor {
        private final String toolName;
        private RemoteExecutor(String toolName) { this.toolName = toolName; }
        @Override public ToolOutput execute(ToolInput input) {
            try { return client.invoke(toolName, input); }
            catch (Exception ex) { return ToolOutput.failure(ex); }
        }
    }

    private McpToolDefinition definition(String name, String title, String description,
                                         List<ToolParameter> parameters, boolean callable, int seconds) {
        return new McpToolDefinition(name, title, description, McpCapabilityCodes.NEWS, "chatchat-runtime-news",
            parameters, true, callable, Duration.ofSeconds(seconds));
    }
    private ToolParameter text(String name, String description, boolean required) {
        return ToolParameter.builder().name(name).type("string").description(description).required(required).build();
    }
    private ToolParameter array(String name, String description) {
        return ToolParameter.builder().name(name).type("array").description(description).required(false).build();
    }
    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
