package com.chatchat.mcpserver.authorization;

import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpAuthorizationService {

    private static final String ALLOW = "allow";
    private static final String DENY = "deny";

    private final McpAuthorizationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>(Snapshot.empty());
    private volatile String bearerToken;

    @PostConstruct
    public void initialize() {
        if (properties.isEnabled()) {
            refreshSafely();
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.authorization.refresh-interval-ms:60000}")
    public void refreshSafely() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            snapshotRef.set(fetchSnapshot());
        } catch (Exception ex) {
            log.warn("Failed to refresh MCP authorization snapshot: {}", ex.getMessage());
        }
    }

    public AuthorizationDecision authorize(String toolName, Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            return AuthorizationDecision.allowDecision();
        }
        Snapshot snapshot = snapshotRef.get();
        if (!snapshot.usable()) {
            if (properties.isFailOpen()) {
                return AuthorizationDecision.allowDecision();
            }
            return AuthorizationDecision.denyDecision("MCP authorization snapshot is unavailable");
        }
        if (snapshot.isStale(properties.getStaleTtlSeconds()) && !properties.isFailOpen()) {
            return AuthorizationDecision.denyDecision("MCP authorization snapshot is stale");
        }

        Principal principal = principal(arguments, snapshot);
        if (principal.tenantId() == null && principal.userId() == null && principal.username() == null) {
            return AuthorizationDecision.denyDecision("MCP caller identity is missing");
        }
        if (isAdminPrincipal(principal)) {
            return AuthorizationDecision.allowDecision();
        }

        List<ToolPermission> matched = snapshot.matchedPermissions(principal);
        if (matched.isEmpty()) {
            return AuthorizationDecision.allowDecision();
        }

        String normalizedToolName = normalize(toolName);
        McpScopeExpression requestedScope = requestedScope(toolName, arguments, principal);
        List<ToolPermission> effective = matched.stream()
            .filter(permission -> permission.matchesTool(normalizedToolName) || permission.matchesScope(requestedScope))
            .toList();
        boolean denied = effective.stream().anyMatch(permission -> DENY.equals(permission.effect()));
        if (denied) {
            return AuthorizationDecision.denyDecision("no permission to execute mcp tool: " + toolName);
        }
        boolean hasAllowList = matched.stream().anyMatch(permission -> ALLOW.equals(permission.effect()));
        boolean allowed = effective.stream().anyMatch(permission -> ALLOW.equals(permission.effect()));
        if (hasAllowList && !allowed) {
            return AuthorizationDecision.denyDecision("mcp tool is not included in caller allow list: " + toolName);
        }
        return AuthorizationDecision.allowDecision();
    }

    private boolean isAdminPrincipal(Principal principal) {
        return "admin".equalsIgnoreCase(principal.userId())
            || "admin".equalsIgnoreCase(principal.username());
    }

    public AuthorizationSyncView currentView() {
        Snapshot snapshot = snapshotRef.get();
        return new AuthorizationSyncView(
            properties.isEnabled(),
            properties.isRequireTenantContext(),
            snapshot.usable(),
            snapshot.refreshedAt(),
            snapshot.isStale(properties.getStaleTtlSeconds()),
            snapshot.usersById().values().stream()
                .filter(user -> user.username() == null || !"admin".equalsIgnoreCase(user.username()))
                .map(user -> new UserView(user.id(), user.tenantId(), user.username(), user.roleIds()))
                .toList(),
            snapshot.rolesById().values().stream()
                .filter(role -> !"admin".equalsIgnoreCase(role.roleCode()))
                .map(role -> new RoleView(
                    role.id(),
                    role.tenantId(),
                    role.roleCode(),
                    role.roleName(),
                    role.roleType(),
                    role.status()
                ))
                .toList(),
            snapshot.tools().stream()
                .map(tool -> new ToolView(
                    tool.id(),
                    tool.localToolName(),
                    tool.serviceId(),
                    tool.serviceName(),
                    tool.remoteToolName(),
                    tool.resourceType(),
                    tool.description(),
                    tool.enabled(),
                    tool.status()
                ))
                .toList(),
            snapshot.permissions().stream()
                .map(permission -> new PermissionView(
                    permission.tenantId(),
                    permission.targetType(),
                    permission.targetId(),
                    permission.localToolName(),
                    permission.scopeExpression(),
                    permission.effect(),
                    permission.enabled(),
                    permission.expiresAt()
                ))
                .toList()
        );
    }

    public AuthorizationSyncView refreshNow() {
        try {
            snapshotRef.set(fetchSnapshot());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to refresh MCP authorization snapshot: " + ex.getMessage(), ex);
        }
        return currentView();
    }

    public List<JsonNode> rolePermissions(String roleId, String tenantId) {
        try {
            String query = tenantId == null || tenantId.isBlank()
                ? ""
                : "?tenantId=" + encode(tenantId);
            JsonNode data = apiJson("GET", "/api/v1/enterprise/tool-permissions" + query, null);
            if (!data.isArray()) {
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode node : data) {
                if ("role".equalsIgnoreCase(node.path("targetType").asText())
                    && roleId.equals(node.path("targetId").asText())) {
                    result.add(node);
                }
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load role permissions: " + ex.getMessage(), ex);
        }
    }

    public JsonNode createRolePermission(RolePermissionRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", blankToNull(request.tenantId()));
            body.put("targetType", "role");
            body.put("targetId", request.roleId());
            body.put("toolId", blankToNull(request.toolId()));
            body.put("localToolName", request.localToolName());
            body.put("effect", request.effect() == null || request.effect().isBlank() ? ALLOW : request.effect());
            body.put("enabled", request.enabled() == null || request.enabled());
            body.put("remark", blankToNull(request.remark()));
            JsonNode saved = apiJson("POST", "/api/v1/enterprise/tool-permissions", objectMapper.writeValueAsString(body));
            refreshSafely();
            return saved;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to save role permission: " + ex.getMessage(), ex);
        }
    }

    public void deleteRolePermission(String id) {
        try {
            apiJson("DELETE", "/api/v1/enterprise/tool-permissions/" + encode(id), null);
            refreshSafely();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to delete role permission: " + ex.getMessage(), ex);
        }
    }

    private Snapshot fetchSnapshot() throws Exception {
        String token = resolveBearerToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri(properties.getSnapshotPath()))
            .timeout(Duration.ofSeconds(15))
            .GET();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && isLoginAuthConfigured()) {
            bearerToken = null;
            token = resolveBearerToken();
            builder = HttpRequest.newBuilder()
                .uri(uri(properties.getSnapshotPath()))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Authorization", "Bearer " + token);
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("snapshot endpoint returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.has("data") ? root.get("data") : root;
        Snapshot snapshot = Snapshot.from(data);
        log.info("MCP authorization snapshot refreshed users={} roles={} permissions={}",
            snapshot.usersById().size(), snapshot.rolesById().size(), snapshot.permissions().size());
        return snapshot;
    }

    private String resolveBearerToken() throws Exception {
        McpAuthorizationProperties.Auth auth = properties.getAuth();
        if (!auth.isEnabled()) {
            return null;
        }
        if (auth.getBearerToken() != null && !auth.getBearerToken().isBlank()) {
            return auth.getBearerToken().trim();
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            return bearerToken;
        }
        if (!isLoginAuthConfigured()) {
            return null;
        }
        String body = objectMapper.writeValueAsString(Map.of(
            "username", auth.getUsername(),
            "password", auth.getPassword()
        ));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri(auth.getLoginPath()))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("login endpoint returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        bearerToken = root.path("data").path("token").asText("");
        return bearerToken;
    }

    private JsonNode apiJson(String method, String path, String body) throws Exception {
        String token = resolveBearerToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri(path))
            .timeout(Duration.ofSeconds(15));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("api endpoint returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.has("data") ? root.get("data") : root;
    }

    private boolean isLoginAuthConfigured() {
        McpAuthorizationProperties.Auth auth = properties.getAuth();
        return auth.isEnabled()
            && auth.getUsername() != null
            && !auth.getUsername().isBlank()
            && auth.getPassword() != null
            && !auth.getPassword().isBlank();
    }

    private URI uri(String path) {
        String base = properties.getApiBaseUrl() == null ? "" : properties.getApiBaseUrl().trim();
        String normalizedPath = path == null || path.isBlank() ? "/" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return URI.create(base.replaceAll("/+$", "") + normalizedPath);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Principal principal(Map<String, Object> arguments, Snapshot snapshot) {
        McpInvocationContext.Context context = McpInvocationContext.current();
        String userId = firstText(
            context == null ? null : context.userId(),
            text(arguments, "operatorUserId"),
            text(arguments, "userId"),
            text(arguments, "user_id")
        );
        String username = firstText(
            context == null ? null : context.username(),
            text(arguments, "username"),
            text(arguments, "operator"),
            text(arguments, "caller")
        );
        User user = snapshot.resolveUser(userId, username);
        String resolvedUserId = firstText(userId, user == null ? null : user.id());
        String tenantId = firstText(
            context == null ? null : context.tenantId(),
            text(arguments, "tenantId"),
            text(arguments, "tenant_id"),
            user == null ? null : user.tenantId()
        );
        Set<String> roleIds = new HashSet<>();
        if (user != null) {
            roleIds.addAll(user.roleIds());
        }
        roleIds.addAll(csv(context == null ? null : context.roles()));
        roleIds.addAll(csv(text(arguments, "roles")));
        roleIds.addAll(csv(text(arguments, "roleIds")));
        return new Principal(tenantId, resolvedUserId, username, roleIds);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private Set<String> csv(String value) {
        Set<String> values = new HashSet<>();
        if (value == null || value.isBlank()) {
            return values;
        }
        for (String item : value.split(",")) {
            if (item != null && !item.isBlank()) {
                values.add(item.trim());
            }
        }
        return values;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private McpScopeExpression requestedScope(String toolName, Map<String, Object> arguments, Principal principal) {
        McpInvocationContext.Context context = McpInvocationContext.current();
        String explicit = firstText(
            context == null ? null : context.scopeExpression(),
            text(arguments, "scopeExpression")
        );
        if (explicit != null) {
            try {
                return McpScopeExpression.parse(explicit);
            } catch (IllegalArgumentException ex) {
                log.debug("Ignoring invalid MCP scope expression {}: {}", explicit, ex.getMessage());
            }
        }
        ToolScope toolScope = toolScope(toolName);
        return McpScopeExpression.of(
            firstText(context == null ? null : context.assetType(), text(arguments, "assetType"), toolScope.assetType()),
            firstText(toolScope.capability(), text(arguments, "capability")),
            firstText(toolScope.action(), "query"),
            principal == null ? null : principal.tenantId(),
            firstText(context == null ? null : context.domain(), text(arguments, "domain")),
            firstText(context == null ? null : context.permissionLevel(), text(arguments, "permissionLevel"), "read")
        );
    }

    private ToolScope toolScope(String toolName) {
        String semantic = semanticToolName(toolName);
        if (semantic.endsWith("_asset_query")) {
            return new ToolScope(normalizeAssetType(semantic.substring(0, semantic.length() - "_asset_query".length())), "asset", "query");
        }
        if (semantic.endsWith("_template_query")) {
            return new ToolScope(normalizeAssetType(semantic.substring(0, semantic.length() - "_template_query".length())), "template", "query");
        }
        return switch (semantic) {
            case "api_asset_query" -> new ToolScope("api_service", "asset", "query");
            case "api_template_query" -> new ToolScope("api_service", "template", "query");
            case "document_search" -> new ToolScope("document", "document", "search");
            case "database_query" -> new ToolScope("database_query", "execute", "query");
            case "linux_command_execute" -> new ToolScope("ssh_host", "execute", "command");
            default -> new ToolScope(null, null, null);
        };
    }

    private String semanticToolName(String toolName) {
        String normalized = normalize(toolName);
        if (normalized == null) {
            return "";
        }
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        if ("api".equals(normalized)) {
            return "api_service";
        }
        return normalized;
    }

    private String normalizeAssetType(String value) {
        String normalized = normalize(value);
        if ("api".equals(normalized)) {
            return "api_service";
        }
        if ("ssh".equals(normalized)) {
            return "ssh_host";
        }
        return normalized;
    }

    public record AuthorizationDecision(boolean allowed, String reason) {
        public static AuthorizationDecision allowDecision() {
            return new AuthorizationDecision(true, null);
        }

        public static AuthorizationDecision denyDecision(String reason) {
            return new AuthorizationDecision(false, reason);
        }
    }

    public record AuthorizationSyncView(
        boolean enabled,
        boolean requireTenantContext,
        boolean snapshotAvailable,
        Instant refreshedAt,
        boolean stale,
        List<UserView> users,
        List<RoleView> roles,
        List<ToolView> tools,
        List<PermissionView> permissions
    ) {
    }

    public record UserView(String id, String tenantId, String username, List<String> roleIds) {
    }

    public record RoleView(String id, String tenantId, String roleCode, String roleName, String roleType, String status) {
    }

    public record ToolView(
        String id,
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String resourceType,
        String description,
        boolean enabled,
        String status
    ) {
    }

    public record RolePermissionRequest(
        String tenantId,
        String roleId,
        String toolId,
        String localToolName,
        String effect,
        Boolean enabled,
        String remark
    ) {
    }

    public record PermissionView(
        String tenantId,
        String targetType,
        String targetId,
        String localToolName,
        String scopeExpression,
        String effect,
        boolean enabled,
        Instant expiresAt
    ) {
    }

    private record Principal(String tenantId, String userId, String username, Set<String> roleIds) {
    }

    private record User(String id, String tenantId, String username, List<String> roleIds) {
    }

    private record Role(String id, String tenantId, String roleCode, String roleName, String roleType, String status) {
    }

    private record Tool(
        String id,
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String resourceType,
        String description,
        boolean enabled,
        String status
    ) {
    }

    private record ToolPermission(
        String tenantId,
        String targetType,
        String targetId,
        String toolId,
        String localToolName,
        String scopeExpression,
        String effect,
        boolean enabled,
        Instant expiresAt
    ) {
        boolean active() {
            return enabled && (expiresAt == null || expiresAt.isAfter(Instant.now()));
        }

        boolean matchesTool(String toolName) {
            String local = normalize(localToolName);
            String id = normalize(toolId);
            return "*".equals(local)
                || "*".equals(id)
                || (toolName != null && (toolName.equals(local) || toolName.equals(id)));
        }

        boolean matchesScope(McpScopeExpression requestedScope) {
            if (scopeExpression == null || scopeExpression.isBlank() || requestedScope == null) {
                return false;
            }
            try {
                McpScopeExpression permissionScope = McpScopeExpression.parse(scopeExpression);
                return permissionScope.matches(
                    requestedScope.assetType(),
                    requestedScope.capability(),
                    requestedScope.action(),
                    requestedScope.tenantId()
                );
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }

    private record Snapshot(
        Instant refreshedAt,
        Map<String, User> usersById,
        Map<String, User> usersByUsername,
        Map<String, Role> rolesById,
        Map<String, String> roleCodeToId,
        List<Tool> tools,
        List<ToolPermission> permissions
    ) {
        static Snapshot empty() {
            return new Snapshot(Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        }

        static Snapshot from(JsonNode data) {
            Map<String, User> usersById = new LinkedHashMap<>();
            Map<String, User> usersByUsername = new LinkedHashMap<>();
            JsonNode users = data.path("users");
            if (users.isArray()) {
                for (JsonNode node : users) {
                    List<String> roleIds = new ArrayList<>();
                    JsonNode roles = node.path("roleIds");
                    if (roles.isArray()) {
                        roles.forEach(role -> roleIds.add(role.asText()));
                    }
                    User user = new User(
                        node.path("id").asText(null),
                        node.path("tenantId").asText(null),
                        node.path("username").asText(null),
                        roleIds
                    );
                    if (user.id() != null) {
                        usersById.put(normalize(user.id()), user);
                    }
                    if (user.username() != null) {
                        usersByUsername.put(normalize(user.username()), user);
                    }
                }
            }

            Map<String, Role> rolesById = new LinkedHashMap<>();
            Map<String, String> roleCodeToId = new LinkedHashMap<>();
            JsonNode roles = data.path("roles");
            if (roles.isArray()) {
                for (JsonNode node : roles) {
                    Role role = new Role(
                        node.path("id").asText(null),
                        node.path("tenantId").asText(null),
                        node.path("roleCode").asText(null),
                        node.path("roleName").asText(null),
                        node.path("roleType").asText(null),
                        node.path("status").asText(null)
                    );
                    if (role.id() != null) {
                        rolesById.put(normalize(role.id()), role);
                    }
                    if (role.roleCode() != null) {
                        roleCodeToId.put(normalize(role.roleCode()), role.id());
                    }
                }
            }

            List<Tool> tools = new ArrayList<>();
            JsonNode toolNodes = data.path("tools");
            if (toolNodes.isArray()) {
                for (JsonNode node : toolNodes) {
                    tools.add(new Tool(
                        node.path("id").asText(null),
                        node.path("localToolName").asText(null),
                        node.path("serviceId").asText(null),
                        node.path("serviceName").asText(null),
                        node.path("remoteToolName").asText(null),
                        node.path("resourceType").asText(null),
                        node.path("description").asText(null),
                        node.path("enabled").asBoolean(true),
                        node.path("status").asText(null)
                    ));
                }
            }

            List<ToolPermission> permissions = new ArrayList<>();
            JsonNode permissionNodes = data.path("permissions");
            if (permissionNodes.isArray()) {
                for (JsonNode node : permissionNodes) {
                    permissions.add(new ToolPermission(
                        node.path("tenantId").asText(null),
                        node.path("targetType").asText(null),
                        node.path("targetId").asText(null),
                        node.path("toolId").asText(null),
                        node.path("localToolName").asText(null),
                        firstText(node.path("scopeExpression").asText(null), node.path("scope").asText(null)),
                        normalize(node.path("effect").asText(ALLOW)),
                        node.path("enabled").asBoolean(true),
                        parseInstant(node.path("expiresAt").asText(null))
                    ));
                }
            }
            return new Snapshot(Instant.now(), usersById, usersByUsername, rolesById, roleCodeToId, tools, permissions);
        }

        boolean usable() {
            return refreshedAt != null && !Instant.EPOCH.equals(refreshedAt);
        }

        boolean isStale(long ttlSeconds) {
            return ttlSeconds > 0 && refreshedAt.plusSeconds(ttlSeconds).isBefore(Instant.now());
        }

        User resolveUser(String userId, String username) {
            User user = userId == null ? null : usersById.get(normalize(userId));
            if (user == null && username != null) {
                user = usersByUsername.get(normalize(username));
            }
            return user;
        }

        List<ToolPermission> matchedPermissions(Principal principal) {
            Set<String> roleIds = new HashSet<>();
            if (principal.roleIds() != null) {
                principal.roleIds().forEach(role -> {
                    String normalized = normalize(role);
                    if (normalized != null) {
                        roleIds.add(normalized);
                        String roleId = roleCodeToId.get(normalized);
                        if (roleId != null) {
                            roleIds.add(normalize(roleId));
                        }
                    }
                });
            }
            List<ToolPermission> matched = new ArrayList<>();
            for (ToolPermission permission : permissions) {
                if (!permission.active() || !sameTenant(permission.tenantId(), principal.tenantId())) {
                    continue;
                }
                String targetType = normalize(permission.targetType());
                String targetId = normalize(permission.targetId());
                if ("tenant".equals(targetType) && targetId != null && targetId.equals(normalize(principal.tenantId()))) {
                    matched.add(permission);
                } else if ("user".equals(targetType) && targetId != null
                    && (targetId.equals(normalize(principal.userId())) || targetId.equals(normalize(principal.username())))) {
                    matched.add(permission);
                } else if ("role".equals(targetType) && targetId != null && roleIds.contains(targetId)) {
                    matched.add(permission);
                }
            }
            return matched;
        }

        private boolean sameTenant(String permissionTenantId, String callerTenantId) {
            String permissionTenant = normalize(permissionTenantId);
            if (permissionTenant == null) {
                return true;
            }
            return permissionTenant.equals(normalize(callerTenantId));
        }

        private static Instant parseInstant(String value) {
            if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private record ToolScope(String assetType, String capability, String action) {
    }
}
