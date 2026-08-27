package com.chatchat.integration.mcp.service.routing;

import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns API-side routing for dynamically published MCP child tools.
 *
 * <p>The API exposes the child tool to agents, but invokes its fixed parent tool
 * on the same MCP service. Authorization and template set filtering remain an
 * MCP-server responsibility.</p>
 */
@Service
public class DynamicMcpToolRouteService {

    /** Rolling-upgrade compatibility for pre-contract template-query publishers. */
    @Deprecated(forRemoval = false)
    public static final String CHILD_TOOL_ARGUMENT = "_templateQueryChildToolName";

    private static final String LEGACY_DYNAMIC_TEMPLATE_QUERY_KIND =
        "dynamic_authorized_template_discovery";
    private static final String LEGACY_ROUTING_MODE =
        "api_parent_mcp_policy_filter";

    private final Map<String, RouteDefinition> routes = new ConcurrentHashMap<>();
    private final java.util.Set<String> protectedIdentityArguments = ConcurrentHashMap.newKeySet();

    public DynamicMcpToolRouteService() {
        protectedIdentityArguments.add(CHILD_TOOL_ARGUMENT);
    }

    public Optional<RouteDefinition> register(String serviceId, McpToolDefinition definition) {
        if (definition == null || definition.meta() == null) return Optional.empty();
        Optional<McpDynamicCapabilityRoute> declared =
            McpDynamicCapabilityRoute.fromToolMetadata(definition.meta());
        McpDynamicCapabilityRoute contract = declared.orElseGet(() -> legacyContract(definition.meta()));
        if (contract == null) return Optional.empty();

        String normalizedServiceId = required(serviceId, "MCP service id");
        String childToolName = required(definition.name(), "dynamic child tool name");
        String parentToolName = publicBridgeParent(contract.parentToolName());
        if (childToolName.equalsIgnoreCase(parentToolName)) {
            throw new IllegalArgumentException("Dynamic MCP child tool cannot route to itself");
        }

        RouteDefinition route = new RouteDefinition(
            normalizedServiceId, childToolName, parentToolName,
            contract.implementationIdentityArgument(), contract.routingMode(),
            contract.contractVersion());
        protectedIdentityArguments.add(route.implementationIdentityArgument());
        routes.put(routeKey(normalizedServiceId, childToolName), route);
        return Optional.of(route);
    }

    public InvocationPlan plan(String serviceId, String requestedToolName,
                               Map<String, Object> arguments) {
        RouteDefinition route = routes.get(routeKey(serviceId, requestedToolName));
        return route == null
            ? directPlan(serviceId, requestedToolName, arguments)
            : plan(route, arguments);
    }

    public InvocationPlan plan(RouteDefinition route, Map<String, Object> arguments) {
        if (route == null) {
            throw new IllegalArgumentException("Dynamic MCP route is required");
        }
        Map<String, Object> routedArguments = mutableArguments(arguments);
        // The child identity is server-discovered routing state, never caller input.
        routedArguments.put(route.implementationIdentityArgument(), route.childToolName());
        return new InvocationPlan(
            route.serviceId(), route.childToolName(), route.parentToolName(), route.childToolName(),
            immutableArguments(routedArguments), route.routingMode(), true);
    }

    public void clear() {
        routes.clear();
    }

    public void unregister(String serviceId, String childToolName) {
        routes.remove(routeKey(serviceId, childToolName));
    }

    public boolean hasImplementation(String serviceId, String parentToolName) {
        if (serviceId == null || parentToolName == null) return false;
        return routes.values().stream().anyMatch(route ->
            route.serviceId().equalsIgnoreCase(serviceId.trim())
                && route.parentToolName().equalsIgnoreCase(parentToolName.trim()));
    }

    private InvocationPlan directPlan(String serviceId, String requestedToolName,
                                      Map<String, Object> arguments) {
        Map<String, Object> directArguments = mutableArguments(arguments);
        // Only a route created from MCP discovery may attach delegated child identity.
        protectedIdentityArguments.forEach(directArguments::remove);
        return new InvocationPlan(
            serviceId, requestedToolName, requestedToolName, null,
            immutableArguments(directArguments), null, false);
    }

    private Map<String, Object> mutableArguments(Map<String, Object> arguments) {
        return new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
    }

    private Map<String, Object> immutableArguments(Map<String, Object> arguments) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    private String routeKey(String serviceId, String childToolName) {
        return String.valueOf(serviceId).trim().toLowerCase(Locale.ROOT) + "\n"
            + String.valueOf(childToolName).trim().toLowerCase(Locale.ROOT);
    }

    private String required(Object value, String label) {
        String result = text(value);
        if (result == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return result;
    }

    private McpDynamicCapabilityRoute legacyContract(Map<String, Object> metadata) {
        if (!LEGACY_DYNAMIC_TEMPLATE_QUERY_KIND.equals(text(metadata.get("kind")))) return null;
        String parent = required(metadata.get("parentToolName"), "parent tool name");
        String legacyMode = text(metadata.get("routingMode"));
        if (legacyMode != null && !LEGACY_ROUTING_MODE.equals(legacyMode)) {
            throw new IllegalArgumentException("Unsupported dynamic MCP routing mode: " + legacyMode);
        }
        return new McpDynamicCapabilityRoute(
            McpDynamicCapabilityRoute.CURRENT_VERSION,
            parent,
            CHILD_TOOL_ARGUMENT,
            LEGACY_ROUTING_MODE,
            Map.of("compatibility", "template-query-v0")
        );
    }

    private String publicBridgeParent(String parentToolName) {
        // The publisher declares the actual invocation target. Routing never derives
        // business semantics from a tool name.
        return parentToolName;
    }

    private String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    public record RouteDefinition(
        String serviceId,
        String childToolName,
        String parentToolName,
        String implementationIdentityArgument,
        String routingMode,
        String contractVersion
    ) {
    }

    public record InvocationPlan(
        String serviceId,
        String requestedToolName,
        String remoteToolName,
        String childToolName,
        Map<String, Object> arguments,
        String routingMode,
        boolean routed
    ) {
    }
}
