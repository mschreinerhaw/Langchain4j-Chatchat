package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.McpScopeExpression;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.McpAdministratorPolicy;
import com.chatchat.mcpserver.external.ExternalMcpRegistryService;
import com.chatchat.mcpserver.external.ExternalMcpService;
import com.chatchat.mcpserver.external.ExternalMcpToolPublisher;
import com.chatchat.mcpserver.license.McpLicenseService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpAuthorizationService {

    private static final String ALLOW = "allow";
    private static final String DENY = "deny";

    private final McpAuthorizationProperties properties;
    private final InternalCredentialProperties internalCredentialProperties;
    private final ObjectMapper objectMapper;
    private final McpSynchronizedRoleRepository roleRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>(Snapshot.empty());
    private final Object snapshotRefreshMonitor = new Object();
    private volatile long lastUnavailableRefreshAttemptMs = Long.MIN_VALUE;
    private volatile String bearerToken;
    private ExternalMcpRegistryService externalMcpRegistryService;
    private McpLicenseService licenseService;
    private ToolRegistry toolRegistry;

    @Autowired
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** The database is the policy authority. Execution never uses the discovery cache. */
    protected JsonNode fetchExecutionSnapshot() throws Exception {
        String path = properties.getSnapshotPath();
        return apiJson("GET", path + (path.contains("?") ? "&" : "?") + "fresh=true", null);
    }

    @Autowired(required = false)
    public void setExternalMcpRegistryService(ExternalMcpRegistryService externalMcpRegistryService) {
        this.externalMcpRegistryService = externalMcpRegistryService;
    }

    @Autowired(required = false)
    public void setLicenseService(McpLicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (properties.isEnabled()) {
            refreshSnapshotSafely("application_ready");
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.authorization.refresh-interval-ms:60000}")
    public void refreshSafely() {
        if (!properties.isEnabled()) {
            return;
        }
        refreshSnapshotSafely("scheduled");
    }

    private boolean refreshSnapshotSafely(String trigger) {
        synchronized (snapshotRefreshMonitor) {
            return refreshSnapshotUnderLock(trigger);
        }
    }

    private Snapshot recoverUnavailableSnapshot() {
        Snapshot snapshot = snapshotRef.get();
        if (snapshot.usable()) {
            return snapshot;
        }
        synchronized (snapshotRefreshMonitor) {
            snapshot = snapshotRef.get();
            if (snapshot.usable()) {
                return snapshot;
            }
            long now = System.currentTimeMillis();
            long retryIntervalMs = Math.max(0L, properties.getUnavailableRetryIntervalMs());
            long lastAttempt = lastUnavailableRefreshAttemptMs;
            if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < retryIntervalMs) {
                return snapshot;
            }
            refreshSnapshotUnderLock("authorization_request");
            return snapshotRef.get();
        }
    }

    private boolean refreshSnapshotUnderLock(String trigger) {
        lastUnavailableRefreshAttemptMs = System.currentTimeMillis();
        try {
            Snapshot snapshot = fetchSnapshot();
            snapshotRef.set(snapshot);
            return snapshot.usable();
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
            log.warn("Failed to refresh MCP authorization snapshot: trigger={} type={} detail={}",
                trigger, ex.getClass().getSimpleName(), detail);
            log.debug("MCP authorization snapshot refresh stack trace", ex);
            return false;
        }
    }

    public AuthorizationDecision authorize(String toolName, Map<String, Object> arguments) {
        return authorize("chatchat-mcp-server", toolName, arguments);
    }

    public AuthorizationDecision authorize(String serviceId, String toolName, Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            return AuthorizationDecision.denyDecision("MCP database authorization is disabled");
        }
        Snapshot snapshot;
        try {
            snapshot = Snapshot.from(fetchExecutionSnapshot());
            snapshotRef.set(snapshot);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("MCP execution authorization unavailable: {}", ex.getClass().getSimpleName());
            return AuthorizationDecision.denyDecision("MCP database execution authorization is unavailable");
        }
        return evaluateAuthorization(serviceId, toolName, arguments, snapshot, null);
    }

    /** Final executor target wins over caller-supplied scope, after deterministic resource resolution. */
    public AuthorizationDecision authorizeResolvedResource(String toolName,String assetType,String resourceId) {
        if(resourceId==null || resourceId.isBlank())return AuthorizationDecision.denyDecision("Resolved resource identity is missing");
        try {
            var snapshot=Snapshot.from(fetchExecutionSnapshot());snapshotRef.set(snapshot);
            var principal=principal(Map.of(),snapshot);
            var requested=requestedScope(toolName,Map.of(),principal);
            var actual=McpScopeExpression.of(assetType,requested.capability(),requested.action(),principal.tenantId(),resourceId,requested.level());
            return evaluateAuthorization("chatchat-mcp-server",toolName,Map.of(),snapshot,actual);
        } catch(Exception ex) {
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            return AuthorizationDecision.denyDecision("Resolved resource authorization is unavailable");
        }
    }

    /** Discovery reuses the execution policy evaluator against the synchronized DB snapshot. */
    public boolean callerAllows(String toolName, McpScopeExpression scope) {
        Snapshot snapshot = snapshotRef.get();
        ToolScope declared = toolScope(toolName);
        McpScopeExpression candidateScope = scope == null ? McpScopeExpression.of(
            declared.assetType(), declared.capability(), declared.action(),
            principal(Map.of(), snapshot).tenantId(), null, "read") : scope;
        return evaluateAuthorization("chatchat-mcp-server", toolName, Map.of(), snapshot, candidateScope).allowed();
    }

    /** Fresh request snapshot; registry domains are evaluated before candidate ranking. */
    public java.util.function.Predicate<String> authorizedAssetDomains(String toolName) {
        if (McpInvocationContext.current() == null || !properties.isEnabled()) return domain -> false;
        final Snapshot snapshot;
        try { snapshot = Snapshot.from(fetchExecutionSnapshot()); }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return domain -> false;
        }
        Principal caller = principal(Map.of(), snapshot);
        ToolScope declared = toolScope(toolName);
        return domain -> domain != null && evaluateAuthorization("chatchat-mcp-server", toolName, Map.of(), snapshot,
            McpScopeExpression.of(declared.assetType(), declared.capability(), declared.action(), caller.tenantId(), domain, "read")).allowed();
    }

    private AuthorizationDecision evaluateAuthorization(String serviceId, String toolName,
                                                         Map<String, Object> arguments, Snapshot snapshot,
                                                         McpScopeExpression discoveryScope) {
        if (!properties.isEnabled()) {
            return AuthorizationDecision.denyDecision("MCP database authorization is disabled");
        }
        if (!snapshot.usable()) {
            return AuthorizationDecision.denyDecision("MCP authorization snapshot is unavailable");
        }
        if (snapshot.isStale(properties.getStaleTtlSeconds())) {
            return AuthorizationDecision.denyDecision("MCP authorization snapshot is stale");
        }

        Principal principal = principal(arguments, snapshot);
        if (principal.tenantId() == null && principal.userId() == null && principal.username() == null) {
            return AuthorizationDecision.denyDecision("MCP caller identity is missing");
        }
        if (properties.isRequireTenantContext() && principal.tenantId() == null) {
            return AuthorizationDecision.denyDecision("MCP caller tenant context is missing");
        }
        if (principal.tenantMismatch()) {
            return AuthorizationDecision.denyDecision("MCP caller tenant does not match synchronized user tenant");
        }
        User synchronizedUser = snapshot.usersById().get(normalize(principal.userId()));
        if (synchronizedUser == null) {
            return AuthorizationDecision.denyDecision("MCP caller is not a synchronized database user");
        }
        if (synchronizedUser != null && synchronizedUser.status() != null
            && !"enabled".equalsIgnoreCase(synchronizedUser.status())) {
            return AuthorizationDecision.denyDecision("MCP caller is disabled");
        }
        String authorizationToolName = delegatedAuthorizationTool(toolName, arguments);
        McpScopeExpression requestedScope;
        try {
            requestedScope = discoveryScope == null
                ? requestedScope(authorizationToolName, arguments, principal) : discoveryScope;
        } catch (IllegalArgumentException ex) {
            return AuthorizationDecision.denyDecision("MCP authorization scope is invalid");
        }
        if (requestedScope.tenantId() != null && !requestedScope.tenantId().equals(principal.tenantId())) {
            return AuthorizationDecision.denyDecision("MCP scope tenant does not match caller tenant");
        }
        String normalizedToolName = normalize(authorizationToolName);
        Set<String> toolIdentities = new HashSet<>();
        toolIdentities.add(normalizedToolName);
        for (Tool tool : snapshot.tools()) {
            // Remote names are scoped to their service; identical names on another
            // MCP server must never inherit this server's grant.
            if (Objects.equals(normalize(serviceId), normalize(tool.serviceId()))
                && (Objects.equals(normalizedToolName, normalize(tool.localToolName()))
                    || Objects.equals(normalizedToolName, normalize(tool.remoteToolName()))
                    || Objects.equals(normalizedToolName, normalize(tool.id())))) {
                if (!tool.enabled()) return AuthorizationDecision.denyDecision("MCP tool is disabled");
                if (normalize(tool.id()) != null) toolIdentities.add(normalize(tool.id()));
                if (normalize(tool.localToolName()) != null) toolIdentities.add(normalize(tool.localToolName()));
                if (normalize(tool.remoteToolName()) != null) toolIdentities.add(normalize(tool.remoteToolName()));
            }
        }
        if (snapshot.isAdministrator(principal)) {
            return AuthorizationDecision.allowDecision();
        }
        List<ToolPermission> matched = snapshot.matchedPermissions(principal);
        if (matched.isEmpty()) {
            return AuthorizationDecision.denyDecision("no MCP asset authorization is assigned to caller");
        }

        List<ToolPermission> effective = matched.stream()
            .filter(permission -> toolIdentities.stream().anyMatch(identity -> permission.matchesRequest(identity, requestedScope)))
            .toList();
        boolean denied = effective.stream().anyMatch(permission -> DENY.equals(permission.effect()));
        if (denied) {
            return AuthorizationDecision.denyDecision("no permission to execute mcp tool: " + authorizationToolName);
        }
        boolean allowed = effective.stream().anyMatch(permission -> ALLOW.equals(permission.effect()));
        if (!allowed) {
            return AuthorizationDecision.denyDecision(
                "mcp tool is not included in caller allow list: " + authorizationToolName);
        }
        return AuthorizationDecision.allowDecision();
    }

    private String delegatedAuthorizationTool(String invokedToolName, Map<String, Object> arguments) {
        if (toolRegistry == null || arguments == null) return invokedToolName;
        for (String childName : toolRegistry.getAllToolNames()) {
            var metadata = toolRegistry.getToolMetadata(childName);
            Map<String, Object> route = map(metadata == null ? null : metadata.getMetadata(), "mcpDynamicCapabilityRoute");
            String identityArgument = text(route, "implementationIdentityArgument");
            if ("parent_delegation".equals(text(route, "routingMode"))
                && Objects.equals(invokedToolName, text(route, "parentToolName"))
                && identityArgument != null
                && Objects.equals(childName, text(arguments, identityArgument))) {
                return childName;
            }
        }
        return invokedToolName;
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
                .map(user -> new UserView(user.id(), user.tenantId(), user.tenantNo(), user.username(), user.roleIds()))
                .toList(),
            localRoleViews(),
            authorizationToolViews(snapshot),
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

    /**
     * Combines the centrally synchronized tool assets with the external MCP
     * tools published by this node. External tools are local dynamic assets,
     * so they must be exposed here immediately after approval instead of
     * waiting for a separate chatchat-api registry synchronization cycle.
     */
    private List<ToolView> authorizationToolViews(Snapshot snapshot) {
        Map<String, ToolView> tools = new LinkedHashMap<>();
        snapshot.tools().stream()
            .filter(tool -> externalMcpRegistryService == null
                || !tool.localToolName().startsWith("external_"))
            .filter(tool -> licenseAllowsTool(tool.localToolName()))
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
            .forEach(tool -> tools.put(tool.localToolName(), tool));
        if (externalMcpRegistryService == null) {
            return List.copyOf(tools.values());
        }
        try {
            for (ExternalMcpService service : externalMcpRegistryService.list()) {
                if (!service.isEnabled()) continue;
                String assetType = externalMcpRegistryService.parentAssetType(service);
                for (ExternalMcpRegistryService.ToolTemplate template
                    : externalMcpRegistryService.templates(service)) {
                    if (!template.readOnly()) continue;
                    String publishedName = ExternalMcpToolPublisher.publishedName(
                        service.getId(), template.name());
                    if (!licenseAllowsTool(publishedName)) continue;
                    tools.put(publishedName, new ToolView(
                        publishedName,
                        publishedName,
                        "external:" + service.getId(),
                        service.getName(),
                        template.title(),
                        assetType,
                        template.description(),
                        true,
                        "online"
                    ));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to synchronize external MCP tools into authorization catalog: {}", ex.getMessage());
            log.debug("External MCP authorization catalog synchronization stack trace", ex);
        }
        return List.copyOf(tools.values());
    }

    private boolean licenseAllowsTool(String toolName) {
        return licenseService == null || licenseService.allowsTool(toolName);
    }

    public AuthorizationSyncView refreshNow() {
        try {
            Snapshot snapshot = fetchSnapshot();
            snapshotRef.set(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to refresh MCP authorization snapshot: " + ex.getMessage(), ex);
        }
        return currentView();
    }

    public CallerAuthorizationContext currentCallerContext() {
        Principal principal = principal(Map.of(), snapshotRef.get());
        return new CallerAuthorizationContext(principal.tenantId(), principal.userId(), principal.username(),
            List.copyOf(principal.roleIds()));
    }

    /**
     * Resolves the caller at an MCP tool-handler boundary. The servlet transport
     * context remains authoritative when it is available. Tool arguments are a
     * fallback for MCP SDK execution threads that no longer retain the servlet
     * {@link ThreadLocal}; any role claim is still canonicalized against the
     * synchronized role table and caller tenant before it is accepted.
     */
    public CallerAuthorizationContext currentCallerContext(Map<String, Object> arguments) {
        Principal principal = principal(arguments == null ? Map.of() : arguments, snapshotRef.get());
        return new CallerAuthorizationContext(principal.tenantId(), principal.userId(), principal.username(),
            List.copyOf(principal.roleIds()));
    }

    public List<UserView> roleMembers(String roleId) {
        String requiredRoleId = blankToNull(roleId);
        if (requiredRoleId == null) {
            throw new IllegalArgumentException("roleId is required");
        }
        Snapshot snapshot = snapshotRef.get();
        Role role = snapshot.rolesById().get(normalize(requiredRoleId));
        if (role == null || !snapshot.activeRole(role)) {
            return List.of();
        }
        String roleCode = normalize(role.roleCode());
        return snapshot.usersById().values().stream()
            .filter(user -> snapshot.sameTenant(user.tenantId(), role.tenantId()))
            .filter(user -> user.roleIds() != null && user.roleIds().stream().anyMatch(value -> {
                String normalized = normalize(value);
                return normalize(requiredRoleId).equals(normalized)
                    || (roleCode != null && roleCode.equals(normalized));
            }))
            .map(user -> new UserView(user.id(), user.tenantId(), user.tenantNo(), user.username(), user.roleIds()))
            .sorted(java.util.Comparator.comparing(UserView::username,
                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    public String tenantName(String tenantId) {
        String normalizedTenantId = normalize(tenantId);
        return normalizedTenantId == null ? null : snapshotRef.get().tenantNamesById().get(normalizedTenantId);
    }

    public boolean roleAllows(String roleId, String tenantId, String toolName,
                              McpScopeExpression requestedScope) {
        Snapshot snapshot = snapshotRef.get();
        Role role = snapshot.rolesById().get(normalize(roleId));
        if (role == null || !snapshot.activeRole(role) || !snapshot.sameTenant(role.tenantId(), tenantId)) {
            return false;
        }
        if (McpAdministratorPolicy.isAdministratorRole(tenantId, role.tenantId(),
            role.roleCode(), role.status())) {
            return true;
        }
        String normalizedRoleId = normalize(role.id());
        String normalizedRoleCode = normalize(role.roleCode());
        List<ToolPermission> permissions = snapshot.permissions().stream()
            .filter(ToolPermission::active)
            .filter(permission -> snapshot.sameTenant(permission.tenantId(), tenantId))
            .filter(permission -> "role".equals(normalize(permission.targetType())))
            .filter(permission -> {
                String target = normalize(permission.targetId());
                return target != null && (target.equals(normalizedRoleId) || target.equals(normalizedRoleCode));
            })
            .toList();
        String normalizedToolName = normalize(toolName);
        List<ToolPermission> effective = permissions.stream()
            .filter(permission -> permission.matchesRequest(normalizedToolName, requestedScope))
            .toList();
        if (effective.stream().anyMatch(permission -> DENY.equals(permission.effect()))) {
            return false;
        }
        return effective.stream().anyMatch(permission -> ALLOW.equals(permission.effect()));
    }

    public long authorizationRevision() {
        Instant refreshedAt = snapshotRef.get().refreshedAt();
        return refreshedAt == null ? 0L : refreshedAt.toEpochMilli();
    }

    public List<RoleView> roles(String tenantId) {
        return snapshotRef.get().rolesById().values().stream()
            .filter(role -> tenantId == null || tenantId.isBlank() || Objects.equals(tenantId, role.tenantId()))
            .map(role -> new RoleView(role.id(), role.tenantId(), role.roleCode(), role.roleName(), role.roleType(), role.status()))
            .sorted(java.util.Comparator.comparing(RoleView::id)).toList();
    }

    private List<RoleView> localRoleViews() { return roles(null); }

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

    private synchronized String resolveBearerToken() throws Exception {
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
            "username", authUsername(auth.getUsername()),
            "password", authPassword(auth.getEncryptedPassword(), auth.getPassword())
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
        HttpResponse<String> response = sendApiRequest(method, path, body, token);
        // API login sessions may be invalidated by restart. Renew cached login credentials once;
        // explicit bearer tokens and permission denials remain authoritative.
        if (response.statusCode() == 401
            && (properties.getAuth().getBearerToken() == null || properties.getAuth().getBearerToken().isBlank())
            && isLoginAuthConfigured()) {
            synchronized (this) {
                if (Objects.equals(bearerToken, token)) bearerToken = null;
            }
            response = sendApiRequest(method, path, body, resolveBearerToken());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("api endpoint returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.has("data") ? root.get("data") : root;
    }

    private HttpResponse<String> sendApiRequest(String method, String path, String body, String token) throws Exception {
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
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean isLoginAuthConfigured() {
        McpAuthorizationProperties.Auth auth = properties.getAuth();
        return auth.isEnabled()
            && !authUsername(auth.getUsername()).isBlank()
            && !authPassword(auth.getEncryptedPassword(), auth.getPassword()).isBlank();
    }

    private String authUsername(String configuredUsername) {
        String username = configuredUsername == null ? "" : configuredUsername.trim();
        if (!username.isBlank()) {
            return username;
        }
        return internalCredentialProperties == null ? "" : internalCredentialProperties.resolvedUsername();
    }

    private String authPassword(String encryptedPassword, String plainPassword) {
        if (internalCredentialProperties == null) {
            return plainPassword == null ? "" : plainPassword.trim();
        }
        String resolved = internalCredentialProperties.resolveSecret(encryptedPassword, plainPassword);
        return resolved.isBlank() ? internalCredentialProperties.resolvedSecret() : resolved;
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
        // An installed transport principal is authoritative, including absent identity fields.
        // Arguments may describe a target user, but cannot impersonate the caller.
        if (context != null) arguments = Map.of();
        Map<String, Object> mcpContext = map(arguments, "mcpContext");
        Map<String, Object> identity = map(mcpContext, "identity");
        String userId = firstText(
            context == null ? null : context.userId(),
            text(arguments, "operatorUserId"),
            text(arguments, "userId"),
            text(arguments, "user_id"),
            text(mcpContext, "userId"),
            text(mcpContext, "user_id"),
            text(identity, "userId"),
            text(identity, "user_id")
        );
        String username = firstText(
            context == null ? null : context.username(),
            text(arguments, "username"),
            text(arguments, "operator"),
            text(arguments, "caller"),
            text(mcpContext, "username"),
            text(identity, "username")
        );
        User user = snapshot.resolveUser(userId, username);
        // Once the caller is found in the synchronized snapshot, always use its
        // canonical identity. Request arguments are tool input and must not be
        // able to replace a normal user's username with another synchronized identity.
        String resolvedUserId = firstText(user == null ? null : user.id(), userId);
        String resolvedUsername = firstText(user == null ? null : user.username(), username);
        String requestedTenantId = firstText(
            context == null ? null : context.tenantId(),
            text(arguments, "tenantId"),
            text(arguments, "tenant_id"),
            text(mcpContext, "tenantId"),
            text(mcpContext, "tenant_id")
        );
        String canonicalTenantId = user == null ? null : firstText(user.tenantId());
        boolean tenantMismatch = canonicalTenantId != null
            && requestedTenantId != null
            && !canonicalTenantId.equalsIgnoreCase(requestedTenantId);
        String tenantId = firstText(canonicalTenantId, requestedTenantId);
        Long tenantNo = user == null ? null : user.tenantNo();
        Set<String> roleIds = new LinkedHashSet<>();
        if (user != null) {
            for (String id : user.roleIds()) {
                Role role = snapshot.rolesById().get(normalize(id));
                if (role != null && Objects.equals(normalize(tenantId), normalize(role.tenantId()))
                    && (role.status() == null || "enabled".equals(normalize(role.status())) || "active".equals(normalize(role.status())))) {
                    roleIds.add(role.id());
                }
            }
        }
        return new Principal(
            tenantId,
            tenantNo,
            resolvedUserId,
            resolvedUsername,
            roleIds,
            user != null,
            tenantMismatch
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
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

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private McpScopeExpression requestedScope(String toolName, Map<String, Object> arguments, Principal principal) {
        McpInvocationContext.Context context = McpInvocationContext.current();
        String explicit = firstText(
            context == null ? null : context.scopeExpression(),
            text(arguments, "scopeExpression")
        );
        ToolScope toolScope = toolScope(toolName);
        var parsed=explicit==null?null:McpScopeExpression.parse(explicit);
        if(parsed!=null && parsed.tenantId()!=null && !Objects.equals(parsed.tenantId(),principal.tenantId()))
            throw new IllegalArgumentException("Scope tenant differs from caller tenant");
        var metadata=toolRegistry==null?null:toolRegistry.getToolMetadata(toolName);
        var resource=map(metadata==null?null:metadata.getMetadata(),"authorizationResource");
        return McpScopeExpression.of(
            firstText(text(resource,"assetType"),toolScope.assetType(),parsed==null?null:parsed.assetType(),context == null ? null : context.assetType(), text(arguments, "assetType")),
            firstText(toolScope.capability(),parsed==null?null:parsed.capability(), text(arguments, "capability")),
            firstText(toolScope.action(),parsed==null?null:parsed.action(), text(arguments, "action")),
            principal == null ? null : principal.tenantId(),
            firstText(text(resource,"domain"),text(arguments,"datasourceId"),text(arguments,"hostId"),text(arguments,"endpointId"),
                parsed==null?null:parsed.domain(),context == null ? null : context.domain(), text(arguments, "domain"), assetDomain(arguments)),
            firstText(parsed==null?null:parsed.level(),context == null ? null : context.permissionLevel(), text(arguments, "permissionLevel"), "read")
        );
    }

    private String assetDomain(Map<String, Object> arguments) {
        return firstText(
            text(arguments, "assetDomain"),
            text(arguments, "assetId"),
            text(arguments, "toolName"),
            text(arguments, "hostId"),
            text(arguments, "datasourceId"),
            text(arguments, "endpointId"),
            text(arguments, "serviceId"),
            text(arguments, "templateId")
        );
    }

    private ToolScope toolScope(String toolName) {
        var metadata = toolRegistry == null ? null : toolRegistry.getToolMetadata(toolName);
        Map<String, Object> declared = map(metadata == null ? null : metadata.getMetadata(), "authorizationScope");
        // Scope semantics belong to the published contract, never to a tool-name switch.
        return new ToolScope(text(declared, "assetType"), text(declared, "capability"), text(declared, "action"));
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

    public record UserView(String id, String tenantId, Long tenantNo, String username, List<String> roleIds) {
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

    private record Principal(
        String tenantId,
        Long tenantNo,
        String userId,
        String username,
        Set<String> roleIds,
        boolean snapshotResolved,
        boolean tenantMismatch
    ) {
    }

    public record CallerAuthorizationContext(String tenantId, String userId, String username,
                                              List<String> roleIds) { }

    private record User(String id, String tenantId, Long tenantNo, String username, List<String> roleIds, String status) {
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

        boolean matchesRequest(String toolName, McpScopeExpression requestedScope) {
            boolean hasToolConstraint = (localToolName != null && !localToolName.isBlank())
                || (toolId != null && !toolId.isBlank());
            if (!hasToolConstraint) {
                return false;
            }
            boolean toolMatched = matchesTool(toolName);
            if (scopeExpression == null || scopeExpression.isBlank()) {
                return toolMatched;
            }
            return toolMatched && matchesScope(requestedScope);
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
                    requestedScope.tenantId(),
                    requestedScope.domain(),
                    requestedScope.level()
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
        Map<String, String> tenantNamesById,
        List<Tool> tools,
        List<ToolPermission> permissions
    ) {
        static Snapshot empty() {
            return new Snapshot(Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
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
                        node.hasNonNull("tenantNo") ? node.path("tenantNo").asLong() : null,
                        node.path("username").asText(null),
                        roleIds,
                        node.path("status").asText(null)
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

            Map<String, String> tenantNamesById = new LinkedHashMap<>();
            JsonNode tenants = data.path("tenants");
            if (tenants.isArray()) {
                for (JsonNode node : tenants) {
                    String tenantId = normalize(node.path("id").asText(null));
                    String tenantName = node.path("tenantName").asText(null);
                    if (tenantId != null && tenantName != null && !tenantName.isBlank()) {
                        tenantNamesById.put(tenantId, tenantName.trim());
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
            return new Snapshot(Instant.now(), usersById, usersByUsername, rolesById, roleCodeToId,
                tenantNamesById, tools, permissions);
        }

        boolean usable() {
            return refreshedAt != null && !Instant.EPOCH.equals(refreshedAt);
        }

        boolean isStale(long ttlSeconds) {
            return ttlSeconds > 0 && refreshedAt.plusSeconds(ttlSeconds).isBefore(Instant.now());
        }

        User resolveUser(String userId, String username) {
            User user = userId == null ? null : usersById.get(normalize(userId));
            // Legacy schedules used the login name as userId. Keep them
            // resolvable while retaining synchronized identity checks.
            if (user == null && userId != null) {
                user = usersByUsername.get(normalize(userId));
            }
            if (user == null && userId == null && username != null) {
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
                    Role role = rolesById.get(targetId);
                    if (role != null && activeRole(role) && sameTenant(role.tenantId(), principal.tenantId())) {
                        matched.add(permission);
                    }
                }
            }
            return matched;
        }

        boolean isAdministrator(Principal principal) {
            if (principal == null || principal.tenantMismatch()) {
                return false;
            }
            // Only synchronized user assignments confer administrator privileges.
            // Validated transport role claims alone must never elevate a caller.
            User user = usersById.get(normalize(principal.userId()));
            return user != null && user.roleIds() != null
                && "enabled".equalsIgnoreCase(user.status())
                && Objects.equals(user.tenantId(), principal.tenantId())
                && user.roleIds().stream().map(id -> rolesById.get(normalize(id)))
                    .filter(Objects::nonNull)
                    .anyMatch(role -> McpAdministratorPolicy.isAdministratorRole(
                        principal.tenantId(), role.tenantId(), role.roleCode(), role.status()));
        }

        private boolean sameTenant(String permissionTenantId, String callerTenantId) {
            String permissionTenant = normalize(permissionTenantId);
            if (permissionTenant == null) {
                return true;
            }
            return permissionTenant.equals(normalize(callerTenantId));
        }

        private boolean activeRole(Role role) {
            String status = role == null ? null : normalize(role.status());
            return status == null || "enabled".equals(status) || "active".equals(status);
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
