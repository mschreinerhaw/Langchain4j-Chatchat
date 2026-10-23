package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateCallerScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class PublishedToolTemplateService {
    private final TemplateCallerScope callers;
    private final ToolReleaseRepository releases;
    private final TemplateQueryBindingService bindings;
    private final TemplateAssetCatalogService catalog;
    private final ResourceAuthorizationPort authorization;
    private final ObjectMapper json;
    public record Template(String key, String title, String description, String category, String content) { }

    public List<Template> templates(String tenant, String actor, String domain) {
        if (domain == null || !domain.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("无效工具");
        try (var ignored = callers.open(tenant, actor)) {
            var context = McpInvocationContext.current();
            Set<String> roles = context.roles() == null || context.roles().isBlank() ? Set.of()
                : new HashSet<>(Arrays.asList(context.roles().split(",")));
            String localName = "mcp_chatchat_mcp_server_" + domain + "_template_query";
            if (!authorization.allowedIds(ResourceAuthorizationPort.MCP_TOOL, tenant, actor, roles, Set.of(localName)).contains(localName))
                throw new SecurityException("无权查看此工具");
            Set<String> selected = new HashSet<>();
            boolean running = false;
            for (var release : releases.findByTenantIdOrderByCreatedAtDesc(tenant)) {
                if (!tenant.equals(release.getTenantId()) || !"RUNNING".equals(release.getState()) || !"CONTROLLED_TEMPLATE".equals(release.getKind())) continue;
                var definition = json.readTree(release.getDefinitionJson());
                if (!domain.equals(definition.path("domainCode").asText())) continue;
                running = true;
                definition.path("templateKeys").forEach(key -> selected.add(key.asText()));
            }
            if (!running) throw new SecurityException("工具已下线或不存在");
            var policy = bindings.allowedTemplates(context, domain + "_template_query");
            Map<String, Template> result = new LinkedHashMap<>();
            for (var template : catalog.businessTemplates(false)) {
                String key = template.templateKey();
                int separator = key.indexOf(':');
                if (separator < 1 || !selected.contains(key) || !policy.getOrDefault(key.substring(0, separator), Set.of()).contains(key.substring(separator + 1))) continue;
                result.putIfAbsent(key, new Template(key, template.title(), template.description(), template.category(), catalog.readableContent(key)));
            }
            return result.values().stream().sorted(Comparator.comparing(Template::title)).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("模板读取失败，请稍后重试", failure);
        }
    }
}
