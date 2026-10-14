package com.chatchat.api.datascience.sql;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolDataType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Additional deny-only application boundary, evaluated after ordinary RBAC.
 * Recognizes server-persisted generation sessions, not a model-controlled profile flag. */
@Component @RequiredArgsConstructor @Order(Ordered.LOWEST_PRECEDENCE)
public class SqlStudioGenerationToolPolicy implements ToolRuntimePolicyProvider {
    private final SqlStudioGenerationScopeStore scopes;

    @Override
    public ToolRuntimePolicy resolve(ToolRuntimeRequest request, ToolMetadata metadata) {
        if (request == null || request.getConversationId() == null) return null;
        for (var scope : scopes.find(request.getConversationId())) {
            if (!scope.tenantId().equals(request.getTenantId()) || !scope.ownerId().equals(request.getUserId()))
                return deny("SQL Studio generation identity does not match its persisted owner");
            return safeMetadata(metadata) ? null : deny("SQL Studio generation permits metadata retrieval only; SQL execution requires a separate authorized operation");
        }
        return null;
    }

    private boolean safeMetadata(ToolMetadata metadata) {
        if (metadata == null || !("read".equals(metadata.getOperationType()) || "read_metadata_index".equals(metadata.getOperationType()))) return false;
        String type = ToolDataType.declared(metadata);
        if (type != null) type = type.toUpperCase(java.util.Locale.ROOT);
        if ("DATA_FETCH".equals(type) || "ACTION_EXECUTION".equals(type) || "TEMPLATE_QUERY".equals(type)) return false;
        Map<String,Object> meta = metadata.getMetadata();
        if (meta == null) return false;
        Object declaration = meta.get("sqlStudioGenerationBoundary");
        if (declaration == null && meta.get("mcpToolMeta") instanceof Map<?,?> nested) declaration = nested.get("sqlStudioGenerationBoundary");
        return declaration instanceof Map<?,?> boundary && "METADATA_READ".equals(boundary.get("operation"))
            && Boolean.FALSE.equals(boundary.get("executesBusinessSql"));
    }
    private ToolRuntimePolicy deny(String reason) { return ToolRuntimePolicy.builder().allowed(false).reason(reason).build(); }
}
