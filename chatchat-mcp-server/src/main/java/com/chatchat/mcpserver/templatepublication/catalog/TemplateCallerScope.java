package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Used only after the control-plane service credential or caller proof was verified. */
@Component @RequiredArgsConstructor
public class TemplateCallerScope {
    private final McpAuthorizationService authorization;

    public McpInvocationContext.Scope open(String tenant, String actor) {
        authorization.refreshTemplateCaller();
        var user = authorization.currentView().users().stream()
            .filter(item -> Objects.equals(item.tenantId(), tenant) && Objects.equals(item.id(), actor))
            .findFirst().orElseThrow(() -> new SecurityException("Unknown template principal"));
        return McpInvocationContext.open(new McpInvocationContext.Context(user.id(), null, null, null,
            "chatchat-mcp-server", user.id(), user.username(), tenant,
            String.join(",", user.roleIds()), null, null, null, null, null, null, null));
    }
}
