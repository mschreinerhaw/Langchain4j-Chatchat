package com.chatchat.chat.runtime;

import com.chatchat.agents.runtime.ToolRuntimeAction;
import com.chatchat.agents.runtime.ToolRuntimeUserPolicyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JpaToolRuntimeUserPolicyStore implements ToolRuntimeUserPolicyStore {

    private final McpUserToolPolicyRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ToolRuntimeAction> findAction(String tenantId, String userId, String toolName) {
        String normalizedTenantId = normalize(tenantId, "default");
        String normalizedUserId = normalize(userId, "anonymous");
        String normalizedToolName = normalize(toolName, "unknown");
        return repository.findByTenantIdAndUserIdAndToolName(normalizedTenantId, normalizedUserId, normalizedToolName)
            .map(McpUserToolPolicyEntity::getAction)
            .map(action -> ToolRuntimeAction.from(action, null));
    }

    @Override
    @Transactional
    public void saveAction(String tenantId, String userId, String toolName, ToolRuntimeAction action) {
        if (action == null) {
            return;
        }
        String normalizedTenantId = normalize(tenantId, "default");
        String normalizedUserId = normalize(userId, "anonymous");
        String normalizedToolName = normalize(toolName, "unknown");
        String id = id(normalizedTenantId, normalizedUserId, normalizedToolName);

        McpUserToolPolicyEntity entity = repository.findById(id).orElseGet(McpUserToolPolicyEntity::new);
        entity.setId(id);
        entity.setTenantId(normalizedTenantId);
        entity.setUserId(normalizedUserId);
        entity.setToolName(normalizedToolName);
        entity.setAction(action.code());
        repository.save(entity);
    }

    private String id(String tenantId, String userId, String toolName) {
        String key = tenantId + "\u0000" + userId + "\u0000" + toolName;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
