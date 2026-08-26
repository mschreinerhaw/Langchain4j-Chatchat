package com.chatchat.integration.mcp.service;

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

    public static final String CHILD_TOOL_ARGUMENT = "_templateQueryChildToolName";

    private static final String DYNAMIC_TEMPLATE_QUERY_KIND =
        "dynamic_authorized_template_discovery";
    private static final String SUPPORTED_ROUTING_MODE =
        "api_parent_mcp_policy_filter";

    private final Map<String, RouteDefinition> routes = new ConcurrentHashMap<>();

    public Optional<RouteDefinition> register(String serviceId, McpToolDefinition definition) {
        if (definition == null || definition.meta() == null
            || !DYNAMIC_TEMPLATE_QUERY_KIND.equals(text(definition.meta().get("kind")))) {
            return Optional.empty();
        }

        String normalizedServiceId = required(serviceId, "MCP service id");
        String childToolName = required(definition.name(), "dynamic child tool name");
        String parentToolName = publicBridgeParent(
            required(definition.meta().get("parentToolName"), "parent tool name"));
        if (childToolName.equalsIgnoreCase(parentToolName)) {
            throw new IllegalArgumentException("Dynamic MCP child tool cannot route to itself");
        }

        String routingMode = text(definition.meta().get("routingMode"));
        if (routingMode == null) {
            routingMode = SUPPORTED_ROUTING_MODE;
        }
        if (!SUPPORTED_ROUTING_MODE.equals(routingMode)) {
            throw new IllegalArgumentException("Unsupported dynamic MCP routing mode: " + routingMode);
        }

        RouteDefinition route = new RouteDefinition(
            normalizedServiceId, childToolName, parentToolName, routingMode);
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
        routedArguments.put(CHILD_TOOL_ARGUMENT, route.childToolName());
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
        directArguments.remove(CHILD_TOOL_ARGUMENT);
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
        String routingMode
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
