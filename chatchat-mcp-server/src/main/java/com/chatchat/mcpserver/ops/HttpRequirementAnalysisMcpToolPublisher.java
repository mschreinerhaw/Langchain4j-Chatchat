package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.routing.RequirementAnalysisProtocol;
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
public class HttpRequirementAnalysisMcpToolPublisher {

    public static final String TOOL_NAME = "http_requirement_analyze";
    private final McpSyncServer mcpSyncServer;
    private final CommandTemplateDiscoveryService templateDiscoveryService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        try {
            mcpSyncServer.removeTool(TOOL_NAME);
        } catch (Exception ex) {
            log.debug("HTTP requirement analysis tool was not registered: {}", ex.getMessage());
        }
        com.chatchat.mcpserver.tool.McpToolPublicationReviewer.addReviewedTool(
            mcpSyncServer, toolSpecification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("HTTP requirement analysis MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification toolSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("HTTP endpoint requirement capability analysis")
            .description("Resolve a model-produced requirement decomposition against registered HTTP endpoint templates. "
                + "Accepts either requirements[] or query shorthand. It returns candidates and gaps but never executes "
                + "HTTP requests and never treats retrieval as semantic acceptance.")
            .inputSchema(new McpSchema.JsonSchema("object", RequirementAnalysisProtocol.inputProperties(),
                List.of(), false, null, null))
            .meta(Map.of(
                "schemaVersion", "http_requirement_analysis.v1",
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
                        .addTextContent("HTTP requirement analysis completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(Map.of(
                            "success", false,
                            "code", "HTTP_REQUIREMENT_PROTOCOL_INVALID",
                            "error", ex.getMessage()))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> analyze(Map<String, Object> arguments) {
        RequirementAnalysisProtocol.NormalizedRequest input = RequirementAnalysisProtocol.normalize(arguments);
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<String> missingRequirementIds = new ArrayList<>();
        for (Map<String, Object> requirement : input.requirements()) {
            String id = String.valueOf(requirement.get("id"));
            Map<String, Object> filters = RequirementAnalysisProtocol.discoveryFilters(
                requirement, input.goal(), input.context());
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("assetType", "http_endpoint");
            query.put("finalDecision", "http");
            query.put("candidates", List.of(Map.of("targetKind", "http", "confidence", 1.0)));
            query.put("filters", filters);
            query.put("limit", input.limitPerRequirement());
            if (input.excludeTemplateIds() != null) {
                query.put("excludeTemplateIds", input.excludeTemplateIds());
            }
            Map<String, Object> discovery = templateDiscoveryService.query(query);
            int returnedCount = RequirementAnalysisProtocol.integer(discovery.get("returnedCount"), 0, 0, Integer.MAX_VALUE);
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
            "schemaVersion", "http_requirement_analysis.v1",
            "success", true,
            "goal", input.goal(),
            "requirementCount", coverage.size(),
            "allRequirementsHaveCandidates", missingRequirementIds.isEmpty(),
            "missingRequirementIds", missingRequirementIds,
            "coverage", coverage,
            "decisionPolicy", "CANDIDATES_FOUND is not semantic acceptance; the model reviewer must accept, refine, or reject candidates before execution.",
            "executionTool", "http_request_execute"
        );
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
