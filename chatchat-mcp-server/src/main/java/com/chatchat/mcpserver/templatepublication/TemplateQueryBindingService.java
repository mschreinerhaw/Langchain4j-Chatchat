package com.chatchat.mcpserver.templatepublication;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.authorization.McpSynchronizedRole;
import com.chatchat.mcpserver.authorization.McpSynchronizedRoleRepository;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.mcp.McpServiceRegistration;
import com.chatchat.mcpserver.mcp.McpServiceRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TemplateQueryBindingService {

    private final TemplateQueryBindingRepository repository;
    private final TemplateAssetCatalogService catalogService;
    private final McpServiceRegistryService serviceRegistryService;
    private final McpSynchronizedRoleRepository roleRepository;
    private final ObjectMapper objectMapper;
    private final McpAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public List<BindingView> list() {
        Map<String, McpServiceRegistration> services = new LinkedHashMap<>();
        serviceRegistryService.listAll().forEach(item -> services.put(item.getId(), item));
        Map<String, McpSynchronizedRole> roles = new LinkedHashMap<>();
        roleRepository.findAll().forEach(item -> roles.put(item.getId(), item));
        return repository.findAllByOrderByUpdatedAtDesc().stream()
            .map(item -> toView(item, services.get(item.getServiceId()), roles.get(item.getRoleId())))
            .toList();
    }

    @Transactional
    public BindingView create(UpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Binding request is required");
        }
        validateUnique(request.serviceId(), request.roleId(), null);
        return save(new TemplateQueryBinding(), request);
    }

    @Transactional
    public BindingView update(String id, UpsertRequest request) {
        TemplateQueryBinding binding = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template query binding not found: " + id));
        validateUnique(request.serviceId(), request.roleId(), id);
        return save(binding, request);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    @Transactional
    public BindingView setEnabled(String id, boolean enabled) {
        TemplateQueryBinding binding = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Template query binding not found: " + id));
        binding.setEnabled(enabled);
        TemplateQueryBinding saved = repository.save(binding);
        return toView(saved, serviceRegistryService.getById(saved.getServiceId()), role(saved.getRoleId()));
    }

    @Transactional(readOnly = true)
    public Map<String, Set<String>> allowedTemplates(McpInvocationContext.Context context) {
        if (context == null || context.clientId() == null || context.clientId().isBlank()) {
            return Map.of();
        }
        McpAuthorizationService.CallerAuthorizationContext caller = authorizationService.currentCallerContext();
        Set<String> callerRoles = new LinkedHashSet<>();
        if (caller != null && caller.roleIds() != null) {
            caller.roleIds().stream().map(this::normalize).filter(item -> item != null).forEach(callerRoles::add);
        }
        String tenantId = normalize(caller == null ? null : caller.tenantId());
        if (tenantId == null || callerRoles.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        for (TemplateQueryBinding binding : repository.findByServiceIdAndEnabledTrue(context.clientId().trim())) {
            if (!tenantId.equals(normalize(binding.getTenantId()))) {
                continue;
            }
            McpSynchronizedRole role = roleRepository.findById(binding.getRoleId()).orElse(null);
            if (role == null || !roleMatches(role, callerRoles)) {
                continue;
            }
            for (String key : readKeys(binding.getTemplateKeysJson())) {
                int separator = key.indexOf(':');
                if (separator <= 0 || separator == key.length() - 1) {
                    continue;
                }
                allowed.computeIfAbsent(key.substring(0, separator), ignored -> new LinkedHashSet<>())
                    .add(key.substring(separator + 1));
            }
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        allowed.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private BindingView save(TemplateQueryBinding binding, UpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Binding request is required");
        }
        McpServiceRegistration service = serviceRegistryService.getById(required(request.serviceId(), "serviceId"));
        McpSynchronizedRole role = role(required(request.roleId(), "roleId"));
        List<String> keys = normalizeKeys(request.templateKeys());
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("At least one template must be selected");
        }
        binding.setTenantId(required(role.getTenantId(), "Role tenantId"));
        binding.setServiceId(service.getId());
        binding.setRoleId(role.getId());
        binding.setTemplateKeysJson(ModelProtocolJson.compact(keys));
        binding.setEnabled(request.enabled() == null || request.enabled());
        return toView(repository.save(binding), service, role);
    }

    private void validateUnique(String serviceId, String roleId, String currentId) {
        String service = required(serviceId, "serviceId");
        String role = required(roleId, "roleId");
        boolean duplicate = currentId == null
            ? repository.existsByServiceIdAndRoleId(service, role)
            : repository.existsByServiceIdAndRoleIdAndIdNot(service, role, currentId);
        if (duplicate) {
            throw new IllegalArgumentException("The service and role already have a template query binding");
        }
    }

    private List<String> normalizeKeys(List<String> keys) {
        Set<String> enabledKeys = catalogService.listEnabled().stream()
            .map(TemplateAssetCatalogService.TemplateAsset::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return keys == null ? List.of() : keys.stream()
            .filter(key -> key != null && !key.isBlank())
            .map(String::trim)
            .distinct()
            .peek(key -> {
                if (!enabledKeys.contains(key)) {
                    throw new IllegalArgumentException("Template is not enabled or does not exist: " + key);
                }
            })
            .toList();
    }

    private boolean roleMatches(McpSynchronizedRole role, Set<String> tokens) {
        return tokens.contains(normalize(role.getId())) || tokens.contains(normalize(role.getRoleCode()));
    }

    private McpSynchronizedRole role(String roleId) {
        return roleRepository.findById(roleId)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
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

    private BindingView toView(TemplateQueryBinding binding, McpServiceRegistration service,
                               McpSynchronizedRole role) {
        return new BindingView(binding.getId(), binding.getTenantId(), binding.getServiceId(),
            service == null ? "" : service.getName(), binding.getRoleId(),
            role == null ? "" : role.getRoleCode(), role == null ? "" : role.getRoleName(),
            readKeys(binding.getTemplateKeysJson()), binding.isEnabled(),
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

    public record UpsertRequest(String serviceId, String roleId, List<String> templateKeys, Boolean enabled) { }
    public record BindingView(String id, String tenantId, String serviceId, String serviceName,
                              String roleId, String roleCode, String roleName, List<String> templateKeys,
                              boolean enabled, java.time.Instant createdAt, java.time.Instant updatedAt) { }
}
