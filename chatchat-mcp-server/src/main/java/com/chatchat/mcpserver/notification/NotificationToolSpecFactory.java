package com.chatchat.mcpserver.notification;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NotificationToolSpecFactory {

    private final NotificationSendService sendService;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;

    public McpServerFeatures.SyncToolSpecification toToolSpecification(NotificationChannelConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(config.getDescription())
            .inputSchema(inputSchema())
            .meta(governanceMeta(config))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                config.getToolName(),
                "notification",
                request.arguments(),
                () -> toCallToolResult(sendService.send(config, request.arguments()))))
            .build();
    }

    private McpSchema.JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("receiver", Map.of(
            "type", "string",
            "description", "接收人。邮件可填邮箱，短信可填手机号，企微/钉钉可填群、用户或 @ 标识。"
        ));
        properties.put("title", Map.of(
            "type", "string",
            "description", "通知标题。发送前必须展示给用户确认。"
        ));
        properties.put("content", Map.of(
            "type", "string",
            "description", "通知正文。发送前必须展示给用户确认。"
        ));
        properties.put("level", Map.of(
            "type", "string",
            "description", "通知级别",
            "enum", List.of("INFO", "WARNING", "CRITICAL"),
            "default", "INFO"
        ));
        properties.put("sourceTaskId", Map.of(
            "type", "string",
            "description", "来源 Agent Runtime Task ID，可用于审计追踪。"
        ));
        return new McpSchema.JsonSchema(
            "object",
            properties,
            List.of("receiver", "title", "content"),
            false,
            null,
            null
        );
    }

    private Map<String, Object> governanceMeta(NotificationChannelConfig config) {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "notification");
        governance.put("operation_type", "notify");
        governance.put("risk_level", "forbidden".equals(config.getRuntimeAction()) ? "forbidden" : "high");
        governance.put("data_scope", "external_notification");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap(
            "default", "forbidden".equals(config.getRuntimeAction()) ? "deny" : "ask_before_execute",
            "allow_user_override", false
        ));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "sensitive_params", List.of(),
            "required_preview_params", List.of("receiver", "title", "content", "level", "sourceTaskId")
        ));
        governance.put("audit", mutableMap(
            "enabled", true,
            "log_params", true,
            "log_result_summary", true
        ));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("notification_channel", config.getId(), governance)
        );
        meta.put("channel", config.getChannel().name());
        meta.put("runtime_action", config.getRuntimeAction());
        meta.put("runtimeAction", config.getRuntimeAction());
        meta.put("notificationTool", true);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(config.getToolName(), "notification"));
        return meta;
    }

    private McpSchema.CallToolResult toCallToolResult(NotificationSendResult result) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("success", result.success());
        structured.put("channel", result.channel());
        structured.put("toolName", result.toolName());
        structured.put("statusCode", result.statusCode());
        structured.put("attempts", result.attempts());
        structured.put("notification", result.notification());
        structured.put("responseBody", result.responseBody());
        structured.put("errorMessage", result.errorMessage());
        String text = result.success()
            ? "Notification sent"
            : firstText(result.errorMessage(), "Notification failed");
        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .structuredContent(structured)
            .isError(!result.success())
            .build();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
