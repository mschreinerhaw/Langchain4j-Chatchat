package com.chatchat.mcpserver.templatepublication;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.authorization.McpSynchronizedRole;
import com.chatchat.mcpserver.authorization.McpSynchronizedRoleRepository;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class TemplateQueryBindingService {

    public static final String SUBJECT_ROLE = "ROLE";
    public static final String SUBJECT_USER = "USER";
    private static final long POLICY_CACHE_TTL_MILLIS = 15_000L;

    private final TemplateQueryBindingRepository repository;
    private final TemplateAssetCatalogService catalogService;
    private final TemplateQueryParentCatalog parentCatalog;
    private final McpSynchronizedRoleRepository roleRepository;
    private final ObjectMapper objectMapper;
    private final McpAuthorizationService authorizationService;
    private final ConcurrentMap<PolicyCacheKey, CachedPolicy> policyCache = new ConcurrentHashMap<>();
    private final AtomicLong bindingGeneration = new AtomicLong();

    @Transactional(readOnly = true)
    public List<BindingView> list() {
        Map<String, TemplateQueryParentCatalog.ParentTool> parents = new LinkedHashMap<>();
        parentCatalog.list().forEach(item -> parents.put(normalize(item.toolName()), item));
        Map<String, McpSynchronizedRole> roles = new LinkedHashMap<>();
        roleRepository.findAll().forEach(item -> roles.put(item.getId(), item));
        Map<String, McpAuthorizationService.UserView> users = new LinkedHashMap<>();
        authorizationService.currentView().users().forEach(item -> users.put(normalize(item.id()), item));
        return repository.findAllByOrderByUpdatedAtDesc().stream()
            .map(item -> toView(item, parents.get(normalize(item.getParentToolName())), roles.get(item.getRoleId()),
                users.get(normalize(item.getSubjectId()))))
            .toList();
    }

    @Transactional
    public BindingView create(UpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Binding request is required");
        }
        validateUnique(request, null);
        BindingView saved = save(new TemplateQueryBinding(), request);
        invalidatePolicyCache();
        return saved;
    }

    @Transactional
    public BindingView update(String id, UpsertRequest request) {
        TemplateQueryBinding binding = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template query binding not found: " + id));
        requireRevision(binding, request == null ? null : request.expectedRevision());
        validateUnique(request, id);
        BindingView saved = save(binding, request);
        invalidatePolicyCache();
        return saved;
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
        invalidatePolicyCache();
    }

    @Transactional
    public BindingView setEnabled(String id, boolean enabled) {
        TemplateQueryBinding binding = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template query binding not found: " + id));
        binding.setEnabled(enabled);
        binding.setRevision(Math.max(1L, binding.getRevision() + 1L));
        TemplateQueryBinding saved = repository.save(binding);
        invalidatePolicyCache();
        return toView(saved, parentCatalog.require(saved.getParentToolName()), role(saved.getRoleId()),
            member(saved.getRoleId(), saved.getSubjectType(), saved.getSubjectId()));
    }

    @Transactional(readOnly = true)
    public Map<String, Set<String>> allowedTemplates(McpInvocationContext.Context context) {
        return allowedTemplates(context, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Set<String>> allowedTemplates(McpInvocationContext.Context context, String toolName) {
        return resolvePolicy(context, toolName).allowedTemplates();
    }

    @Transactional(readOnly = true)
    public PolicyResolution resolvePolicy(McpInvocationContext.Context context, String toolName) {
        if (context == null || context.clientId() == null || context.clientId().isBlank()) {
            return PolicyResolution.empty();
        }
        String requiredToolName = toolName == null ? null : TemplateQueryToolNamePolicy.requireToolName(toolName);
        McpAuthorizationService.CallerAuthorizationContext caller = authorizationService.currentCallerContext();
        Set<String> callerRoles = new LinkedHashSet<>();
        if (caller != null && caller.roleIds() != null) {
            caller.roleIds().stream().map(this::normalize).filter(item -> item != null).forEach(callerRoles::add);
        }
        String tenantId = normalize(caller == null ? null : caller.tenantId());
        if (tenantId == null || callerRoles.isEmpty()) {
            return PolicyResolution.empty();
        }
        long generation = bindingGeneration.get();
        PolicyCacheKey cacheKey = new PolicyCacheKey(
            normalize(context.clientId()), requiredToolName, tenantId,
            normalize(caller.userId()), normalize(caller.username()),
            String.join(",", new TreeSet<>(callerRoles)),
            authorizationService.authorizationRevision(), generation);
        CachedPolicy cached = policyCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() >= now) {
            return cached.resolution().withCacheHit(true);
        }
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        Map<String, Set<String>> authorizedKeysByRole = new LinkedHashMap<>();
        Set<String> parentToolNames = new TreeSet<>();
        for (TemplateQueryBinding binding : repository.findByServiceIdAndEnabledTrue(context.clientId().trim())) {
            if (requiredToolName != null && !requiredToolName.equals(toolName(binding.getDomainCode()))) {
                continue;
            }
            if (!tenantId.equals(normalize(binding.getTenantId()))) {
                continue;
            }
            McpSynchronizedRole role = roleRepository.findById(binding.getRoleId()).orElse(null);
            if (role == null || !roleMatches(role, callerRoles)) {
                continue;
            }
            if (SUBJECT_USER.equalsIgnoreCase(binding.getSubjectType())
                && !callerMatchesSubject(caller, binding.getSubjectId())) {
                continue;
            }
            TemplateQueryParentCatalog.ParentTool parent;
            try {
                parent = parentCatalog.require(binding.getParentToolName());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String authorizationKey = role.getId() + "|" + parent.assetType();
            Set<String> authorizedKeys = authorizedKeysByRole.computeIfAbsent(authorizationKey, ignored ->
                catalogService.listAuthorizedForRoleAndType(role.getId(), parent.assetType()).stream()
                    .map(TemplateAssetCatalogService.TemplateAsset::key)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            for (String key : readKeys(binding.getTemplateKeysJson())) {
                if (!key.startsWith(parent.assetType() + ":") || !authorizedKeys.contains(key)) {
                    continue;
                }
                int separator = key.indexOf(':');
                if (separator <= 0 || separator == key.length() - 1) {
                    continue;
                }
                allowed.computeIfAbsent(key.substring(0, separator), ignored -> new LinkedHashSet<>())
                    .add(key.substring(separator + 1));
                parentToolNames.add(parent.toolName());
            }
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        allowed.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        Map<String, Set<String>> result = Map.copyOf(immutable);
        PolicyResolution resolution = new PolicyResolution(result, Set.copyOf(parentToolNames),
            policyVersion(result, parentToolNames, cacheKey), false,
            result.values().stream().mapToInt(Set::size).sum(), Instant.now());
        policyCache.put(cacheKey, new CachedPolicy(resolution, now + POLICY_CACHE_TTL_MILLIS));
        return resolution;
    }

    @Transactional(readOnly = true)
    public Set<String> publishedToolNames() {
        Set<String> names = new TreeSet<>();
        repository.findAllByOrderByUpdatedAtDesc().stream()
            .filter(TemplateQueryBinding::isEnabled)
            .map(TemplateQueryBinding::getDomainCode)
            .map(this::toolName)
            .forEach(names::add);
        return Set.copyOf(names);
    }

    private BindingView save(TemplateQueryBinding binding, UpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Binding request is required");
        }
        TemplateQueryParentCatalog.ParentTool parent = parentCatalog.require(request.parentToolName());
        McpSynchronizedRole role = role(required(request.roleId(), "roleId"));
        String domainCode = TemplateQueryToolNamePolicy.requireDomainCode(request.domainCode());
        Subject subject = subject(role, request.subjectType(), request.userId());
        validateParentConsistency(domainCode, parent.toolName(), binding.getId());
        List<String> keys = normalizeKeys(request.templateKeys(), role.getId(), parent.assetType());
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("At least one template must be selected");
        }
        binding.setTenantId(required(role.getTenantId(), "Role tenantId"));
        binding.setServiceId(parent.serviceId());
        binding.setParentToolName(parent.toolName());
        binding.setRoleId(role.getId());
        binding.setDomainCode(domainCode);
        binding.setSubjectType(subject.type());
        binding.setSubjectId(subject.id());
        binding.setTemplateKeysJson(ModelProtocolJson.compact(keys));
        binding.setEnabled(request.enabled() == null || request.enabled());
        binding.setRevision(binding.getId() == null ? 1L : Math.max(1L, binding.getRevision() + 1L));
        return toView(repository.save(binding), parent, role, subject.user());
    }

    private void validateUnique(UpsertRequest request, String currentId) {
        if (request == null) {
            throw new IllegalArgumentException("Binding request is required");
        }
        TemplateQueryParentCatalog.ParentTool parent = parentCatalog.require(request.parentToolName());
        McpSynchronizedRole role = role(required(request.roleId(), "roleId"));
        String domain = TemplateQueryToolNamePolicy.requireDomainCode(request.domainCode());
        validateParentConsistency(domain, parent.toolName(), currentId);
        Subject subject = subject(role, request.subjectType(), request.userId());
        boolean duplicate = currentId == null
            ? repository.existsByServiceIdAndRoleIdAndDomainCodeAndSubjectTypeAndSubjectId(
                parent.serviceId(), role.getId(), domain, subject.type(), subject.id())
            : repository.existsByServiceIdAndRoleIdAndDomainCodeAndSubjectTypeAndSubjectIdAndIdNot(
                parent.serviceId(), role.getId(), domain, subject.type(), subject.id(), currentId);
        if (duplicate) {
            throw new IllegalArgumentException(
                "The service, role, and template query domain already have a binding");
        }
    }

    private List<String> normalizeKeys(List<String> keys, String roleId, String assetType) {
        Set<String> enabledKeys = catalogService.listAuthorizedForRoleAndType(roleId, assetType).stream()
            .map(TemplateAssetCatalogService.TemplateAsset::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return keys == null ? List.of() : keys.stream()
            .filter(key -> key != null && !key.isBlank())
            .map(String::trim)
            .distinct()
            .peek(key -> {
                if (!enabledKeys.contains(key)) {
                    throw new IllegalArgumentException(
                        "Template is not authorized or does not match the selected parent query: " + key);
                }
            })
            .toList();
    }

    private void validateParentConsistency(String domainCode, String parentToolName, String currentId) {
        boolean mismatch = repository.findByDomainCode(domainCode).stream()
            .filter(binding -> currentId == null || !currentId.equals(binding.getId()))
            .anyMatch(binding -> !parentToolName.equalsIgnoreCase(binding.getParentToolName()));
        if (mismatch) {
            throw new IllegalArgumentException(
                "All bindings of the same dynamic template query tool must use the same parent query");
        }
    }

    private boolean roleMatches(McpSynchronizedRole role, Set<String> tokens) {
        return tokens.contains(normalize(role.getId())) || tokens.contains(normalize(role.getRoleCode()));
    }

    private boolean callerMatchesSubject(McpAuthorizationService.CallerAuthorizationContext caller,
                                         String subjectId) {
        String expected = normalize(subjectId);
        return caller != null && expected != null
            && (expected.equals(normalize(caller.userId())) || expected.equals(normalize(caller.username())));
    }

    private Subject subject(McpSynchronizedRole role, String requestedType, String userId) {
        String type = requestedType == null || requestedType.isBlank()
            ? SUBJECT_ROLE : requestedType.trim().toUpperCase(Locale.ROOT);
        if (SUBJECT_ROLE.equals(type)) {
            return new Subject(type, role.getId(), null);
        }
        if (!SUBJECT_USER.equals(type)) {
            throw new IllegalArgumentException("subjectType must be ROLE or USER");
        }
        String requiredUserId = required(userId, "userId");
        McpAuthorizationService.UserView user = authorizationService.roleMembers(role.getId()).stream()
            .filter(item -> requiredUserId.equalsIgnoreCase(item.id())
                || requiredUserId.equalsIgnoreCase(item.username()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("User is not a member of the selected role"));
        return new Subject(type, user.id(), user);
    }

    private McpAuthorizationService.UserView member(String roleId, String subjectType, String subjectId) {
        if (!SUBJECT_USER.equalsIgnoreCase(subjectType)) {
            return null;
        }
        return authorizationService.roleMembers(roleId).stream()
            .filter(item -> Objects.equals(normalize(item.id()), normalize(subjectId)))
            .findFirst().orElse(null);
    }

    private McpSynchronizedRole role(String roleId) {
        return roleRepository.findById(roleId)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
    }

    private void requireRevision(TemplateQueryBinding binding, Long expectedRevision) {
        if (expectedRevision != null && expectedRevision.longValue() != binding.getRevision()) {
            throw new IllegalStateException("Template query binding was updated by another request; refresh and retry");
        }
    }

    private void invalidatePolicyCache() {
        bindingGeneration.incrementAndGet();
        policyCache.clear();
    }

    private String policyVersion(Map<String, Set<String>> allowed, Set<String> parentToolNames,
                                 PolicyCacheKey key) {
        StringBuilder canonical = new StringBuilder()
            .append(key.serviceId()).append('|').append(key.toolName()).append('|')
            .append(key.tenantId()).append('|').append(key.userId()).append('|')
            .append(key.username()).append('|').append(key.roles());
        new TreeSet<>(parentToolNames).forEach(parent -> canonical.append("|parent=").append(parent));
        allowed.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            canonical.append('|').append(entry.getKey()).append('=');
            new TreeSet<>(entry.getValue()).forEach(value -> canonical.append(value).append(','));
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate template query policy version", ex);
        }
    }

    private List<String> readKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private BindingView toView(TemplateQueryBinding binding,
                               TemplateQueryParentCatalog.ParentTool parent,
                               McpSynchronizedRole role, McpAuthorizationService.UserView user) {
        return new BindingView(binding.getId(), binding.getTenantId(), binding.getServiceId(),
            parent == null ? "" : parent.serviceName(), binding.getParentToolName(),
            parent == null ? "" : parent.title(), parent == null ? "" : parent.assetType(), binding.getRoleId(),
            role == null ? "" : role.getRoleCode(), role == null ? "" : role.getRoleName(),
            binding.getSubjectType(), SUBJECT_USER.equalsIgnoreCase(binding.getSubjectType())
                ? binding.getSubjectId() : null, user == null ? "" : user.username(),
            binding.getDomainCode(), toolName(binding.getDomainCode()),
            readKeys(binding.getTemplateKeysJson()), binding.isEnabled(), binding.getRevision(),
            binding.getCreatedAt(), binding.getUpdatedAt());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toolName(String domainCode) {
        return TemplateQueryToolNamePolicy.toolName(domainCode);
    }

    public record UpsertRequest(String parentToolName, String roleId, String subjectType, String userId, String domainCode,
                                List<String> templateKeys, Boolean enabled, Long expectedRevision) { }
    public record BindingView(String id, String tenantId, String serviceId, String serviceName,
                              String parentToolName, String parentToolTitle, String parentAssetType,
                              String roleId, String roleCode, String roleName, String subjectType,
                              String userId, String username, String domainCode,
                              String toolName, List<String> templateKeys,
                              boolean enabled, long revision,
                              java.time.Instant createdAt, java.time.Instant updatedAt) { }

    public record PolicyResolution(Map<String, Set<String>> allowedTemplates, Set<String> parentToolNames,
                                   String policyVersion,
                                   boolean cacheHit, int configuredTemplateCount, Instant resolvedAt) {
        static PolicyResolution empty() {
            return new PolicyResolution(Map.of(), Set.of(), "deny-all", false, 0, Instant.now());
        }

        PolicyResolution withCacheHit(boolean value) {
            return new PolicyResolution(allowedTemplates, parentToolNames, policyVersion, value,
                configuredTemplateCount, resolvedAt);
        }
    }

    private record Subject(String type, String id, McpAuthorizationService.UserView user) { }
    private record PolicyCacheKey(String serviceId, String toolName, String tenantId,
                                  String userId, String username, String roles,
                                  long authorizationRevision, long bindingGeneration) { }
    private record CachedPolicy(PolicyResolution resolution, long expiresAtMillis) { }
}
