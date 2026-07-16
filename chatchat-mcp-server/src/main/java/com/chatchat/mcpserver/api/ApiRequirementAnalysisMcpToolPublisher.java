package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiRequirementAnalysisMcpToolPublisher {

    public static final String TOOL_NAME = "api_requirement_analyze";
    private static final int MAX_REQUIREMENTS = 20;

    private final McpSyncServer mcpSyncServer;
    private final ApiTemplateDiscoveryMcpToolPublisher templateDiscovery;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        try {
            mcpSyncServer.removeTool(TOOL_NAME);
        } catch (Exception ex) {
            log.debug("API requirement analysis tool was not registered: {}", ex.getMessage());
        }
        mcpSyncServer.addTool(toolSpecification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("API requirement analysis MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification toolSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("API requirement capability analysis")
            .description("Resolve a model-produced requirement decomposition against registered API templates. "
                + "It returns candidates and gaps per requirement, but does not execute APIs and does not claim that a candidate is semantically accepted.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "schemaVersion", Map.of("type", "string"),
                "goal", Map.of("type", "string"),
                "requirements", Map.of(
                    "type", "array",
                    "maxItems", MAX_REQUIREMENTS,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "id", Map.of("type", "string"),
                            "description", Map.of("type", "string"),
                            "requiredOutputs", Map.of("type", "array", "items", Map.of("type", "string")),
                            "dependsOn", Map.of("type", "array", "items", Map.of("type", "string"))
                        ),
                        "required", List.of("id", "description")
                    )
                ),
                "excludeTemplateIds", Map.of("type", "array", "items", Map.of("type", "string")),
                "limitPerRequirement", Map.of("type", "integer", "minimum", 1, "maximum", 10)
            ), List.of("goal", "requirements"), false, null, null))
            .meta(Map.of(
                "schemaVersion", "api_requirement_analysis.v1",
                "runtime_action", "read_only",
                "runtimeAction", "read_only",
                "controlPlane", "discovery",
                "readOnly", true
            ))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = analyze(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("API requirement analysis completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(Map.of("success", false, "code", "API_REQUIREMENT_PROTOCOL_INVALID", "error", ex.getMessage()))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> analyze(Map<String, Object> arguments) {
        String goal = text(arguments == null ? null : arguments.get("goal"));
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        Object rawRequirements = arguments.get("requirements");
        if (!(rawRequirements instanceof List<?> requirements) || requirements.isEmpty()) {
            throw new IllegalArgumentException("requirements must contain at least one requirement");
        }
        if (requirements.size() > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException("requirements exceeds maximum " + MAX_REQUIREMENTS);
        }
        int limit = integer(arguments.get("limitPerRequirement"), 5, 1, 10);
        Object exclusions = arguments.get("excludeTemplateIds");
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<String> missingRequirementIds = new ArrayList<>();
        for (Object item : requirements) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("each requirement must be an object");
            }
            Map<String, Object> requirement = new LinkedHashMap<>((Map<String, Object>) raw);
            String id = text(requirement.get("id"));
            String description = text(requirement.get("description"));
            if (id.isBlank() || description.isBlank()) {
                throw new IllegalArgumentException("requirement id and description are required");
            }
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("intent", description);
            filters.put("goal", goal);
            filters.put("keywords", requirement.getOrDefault("requiredOutputs", List.of()));
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("filters", filters);
            query.put("limit", limit);
            if (exclusions != null) {
                query.put("excludeTemplateIds", exclusions);
            }
            Map<String, Object> discovery = templateDiscovery.query(query);
            int returnedCount = integer(discovery.get("returnedCount"), 0, 0, Integer.MAX_VALUE);
            if (returnedCount == 0) {
                missingRequirementIds.add(id);
            }
            coverage.add(mapOf(
                "requirement", requirement,
                "candidateStatus", returnedCount > 0 ? "CANDIDATES_FOUND" : "NO_CANDIDATE",
                "returnedCount", returnedCount,
                "templates", discovery.getOrDefault("templates", List.of()),
                "selectionProtocol", discovery.get("selectionProtocol")
            ));
        }
        return mapOf(
            "schemaVersion", "api_requirement_analysis.v1",
            "success", true,
            "goal", goal,
            "requirementCount", coverage.size(),
            "allRequirementsHaveCandidates", missingRequirementIds.isEmpty(),
            "missingRequirementIds", missingRequirementIds,
            "coverage", coverage,
            "decisionPolicy", "CANDIDATES_FOUND is not semantic acceptance; the model reviewer must accept, refine, or reject candidates before execution.",
            "executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME
        );
    }

    private int integer(Object value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) {
                map.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return map;
    }
}
