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
            "description", "接收人。邮件可填邮箱，短信可填手机号，企业微信/钉钉可填群、用户或 @ 标识。"
        ));
        properties.put("title", Map.of(
            "type", "string",
            "description", "兼容字段：通知标题。使用 contentProtocol 时由协议中的逐字提取标题覆盖。"
        ));
        properties.put("content", Map.of(
            "type", "string",
            "description", "兼容字段：通知正文。固定答案不得改写；推荐通过 contentProtocol 发送。"
        ));
        properties.put("contentProtocol", Map.of(
            "type", "object",
            "description", "邮件、企业微信、钉钉内容发送协议 chatchat.notification.v1。sourceContent 必须是固定答案原文，sourceSha256 为原文 SHA-256；title 只能逐字取自原文且不超过 120 字；blocks 只能按原行号连续分组，禁止改写、删减或重排。",
            "properties", Map.of(
                "version", Map.of("type", "string", "const", "chatchat.notification.v1"),
                "title", Map.of("type", "string", "maxLength", 120),
                "sourceContent", Map.of("type", "string"),
                "sourceSha256", Map.of("type", "string", "pattern", "^[0-9a-fA-F]{64}$"),
                "format", Map.of("type", "string", "const", "MARKDOWN"),
                "blocks", Map.of("type", "array")
            ),
            "required", List.of("version", "title", "sourceContent", "sourceSha256", "format", "blocks"),
            "additionalProperties", false
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
            List.of("receiver"),
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
            "required_preview_params", List.of("receiver", "title", "content", "contentProtocol", "level", "sourceTaskId")
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
