package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Rich metadata for tool definition
 *
 * Provides comprehensive information about a tool including versioning,
 * categorization, parameters, and enterprise features. Aligns with LangChain
 * tool format while extending for Java enterprise needs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolMetadata {

    /**
     * Unique tool identifier (snake_case, e.g., "web_search")
     */
    private String id;

    /**
     * Human-readable tool name (e.g., "Web Search")
     */
    private String title;

    /**
     * Detailed description of what the tool does
     */
    private String description;

    /**
     * Version of the tool
     */
    @Builder.Default
    private String version = "1.0.0";

    /**
     * Author or maintainer of the tool
     */
    private String author;

    /**
     * Category/tags for tool classification (e.g., "search", "calculation", "system")
     */
    private List<String> categories;

    /**
     * Governance category for MCP runtime policy (e.g., finance_data_query).
     */
    private String category;

    /**
     * Tool risk level: low, medium, high, forbidden.
     */
    @Builder.Default
    private String riskLevel = "low";

    /**
     * Operation type: read, write, send, delete, permission_change.
     */
    @Builder.Default
    private String operationType = "read";

    /**
     * Product runtime level: readonly, suggestion, confirm_required, forbidden.
     */
    private String runtimeLevel;

    /**
     * Whether this tool should be visible in user confirmation surfaces.
     */
    @Builder.Default
    private boolean userVisible = true;

    /**
     * Confirmation policy hints, for example {"default":"ask_before_execute"}.
     */
    private Map<String, Object> confirmation;

    /**
     * Permission hints, for example {"roles":["analyst","manager"]}.
     */
    private Map<String, Object> permissions;

    /**
     * Input governance hints, for example {"must_show_parameters":true}.
     */
    private Map<String, Object> inputPolicy;

    /**
     * Output governance hints, for example {"mask_fields":["phone","id_card"]}.
     */
    private Map<String, Object> outputPolicy;

    /**
     * List of parameter definitions
     */
    private List<ToolParameter> parameters;

    /**
     * Output type description (e.g., "json", "string", "number")
     */
    private String outputType;

    /**
     * Whether tool result should be returned directly to user (bypass further processing)
     */
    @Builder.Default
    private boolean returnDirect = false;

    /**
     * Tool execution timeout in milliseconds
     */
    private Long timeoutMillis;

    /**
     * Whether this tool requires authentication
     */
    @Builder.Default
    private boolean requiresAuth = false;

    /**
     * Whether this tool is rate-limited
     */
    @Builder.Default
    private boolean isRateLimited = false;

    /**
     * Rate limit: maximum calls per minute (0 means unlimited)
     */
    @Builder.Default
    private int maxCallsPerMinute = 0;

    /**
     * Whether this tool can be used in agent context
     */
    @Builder.Default
    private boolean agentCompatible = true;

    /**
     * Tags for additional categorization
     */
    private List<String> tags;

    /**
     * Link to tool documentation
     */
    private String documentationUrl;

    /**
     * Additional metadata
     */
    private Map<String, Object> metadata;
}
