package com.chatchat.mcpserver.document;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

/** Connects MCP-side retrieval and capability discovery to current API database grants. */
@Component
@ConditionalOnProperty(prefix = "chatchat.mcp.server.document-search", name = "api-authorization-enabled",
    havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ApiResourceAuthorizationAdapter implements ResourceAuthorizationPort {
    private final ApiDocumentEvidenceClient apiClient;

    @Override
    public Set<String> allowedIds(String resourceType, String tenantId, String userId,
                                  Set<String> ignoredCallerRoles, Set<String> candidateIds) {
        try {
            if (candidateIds == null || candidateIds.isEmpty()) return Set.of();
            ArrayList<String> ids = new ArrayList<>(candidateIds);
            Set<String> allowed = new HashSet<>();
            for (int offset = 0; offset < ids.size(); offset += 500) {
                allowed.addAll(apiClient.allowedResourceIds(resourceType, tenantId, userId,
                    Set.copyOf(ids.subList(offset, Math.min(offset + 500, ids.size())))));
            }
            return Set.copyOf(allowed);
        } catch (RuntimeException exception) {
            log.warn("MCP resource authorization unavailable type={} tenant={} reason={}",
                resourceType, tenantId, exception.getMessage());
            return Set.of();
        }
    }
}
