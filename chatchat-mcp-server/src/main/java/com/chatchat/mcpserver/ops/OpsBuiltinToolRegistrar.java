package com.chatchat.mcpserver.ops;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.tool.McpServerToolRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class OpsBuiltinToolRegistrar implements McpServerToolRegistrar {

    private final HttpRequestToolService httpRequestToolService;
    private final Environment environment;

    public OpsBuiltinToolRegistrar(HttpRequestToolService httpRequestToolService,
                                   Environment environment) {
        this.httpRequestToolService = httpRequestToolService;
        this.environment = environment;
    }

    @Override
    public void registerTools(ToolRegistry toolRegistry) {
        toolRegistry.registerTool("http_request", httpRequestMetadata(), new HttpRequestTool());
    }

    private ToolMetadata httpRequestMetadata() {
        String confirmationAction = environment.getProperty(
            "chatchat.mcp.ops.http-request.confirmation.default",
            "auto_execute"
        );
        return ToolMetadata.builder()
            .id("http_request")
            .title("HTTP Request")
            .description("Call an HTTP or HTTPS endpoint and return status, parsed body, raw body, headers, and duration.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "ops", "http"))
            .category("ops_http")
            .riskLevel(environment.getProperty("chatchat.mcp.ops.http-request.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("http")
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(60000L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("url")
                    .type("string")
                    .description("HTTP or HTTPS URL to call")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("method")
                    .type("string")
                    .description("HTTP method")
                    .required(false)
                    .defaultValue("GET")
                    .enumValues(new String[]{"GET", "POST", "PUT", "DELETE"})
                    .build(),
                ToolParameter.builder()
                    .name("headers")
                    .type("object")
                    .description("Request headers")
                    .required(false)
                    .metadata(Map.of("additionalProperties", true))
                    .build(),
                ToolParameter.builder()
                    .name("body")
                    .type("object")
                    .description("Request body for POST, PUT, and DELETE. Strings and JSON objects are supported.")
                    .required(false)
                    .metadata(Map.of("additionalProperties", true))
                    .build(),
                ToolParameter.builder()
                    .name("timeoutMs")
                    .type("integer")
                    .description("Request timeout in milliseconds")
                    .required(false)
                    .defaultValue(10000)
                    .minimum(1000)
                    .maximum(60000)
                    .build(),
                ToolParameter.builder()
                    .name("sourceTaskId")
                    .type("string")
                    .description("Optional task identifier for audit correlation")
                    .required(false)
                    .maxLength(128)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "ops", "http", "request"))
            .metadata(Map.of(
                "governanceCategory", "ops_builtin",
                "runtimeAction", "readonly"
            ))
            .build();
    }

    private final class HttpRequestTool implements ToolRegistry.EnhancedTool {

        @Override
        public ToolMetadata getMetadata() {
            return httpRequestMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                Map<String, Object> arguments = input == null || input.getParameters() == null
                    ? Map.of()
                    : input.getParameters();
                return ToolOutput.success(httpRequestToolService.execute(arguments), "HTTP request completed");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }
}
