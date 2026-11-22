package com.chatchat.api.workspace;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class BusinessChatResources {
    private final ObjectAccessService access;
    private final WorkspaceService workspaces;
    private final EnterpriseAdminService identities;
    private final SearchService search;
    private final ResourceAuthorizationPort authorization;
    private final DomainSkillService skills;
    private final SkillCatalogService agents;
    private final SkillResourceScopeRepository scopes;
    private final SkillExecutionScopePort executionScope;

    public record KnowledgeBase(String id, String name, int documentCount) {}

    @GetMapping("/api/v1/workspaces/business/knowledge-bases")
    public ApiResponse<?> list(HttpServletRequest request, @RequestParam(name="keyword", defaultValue = "") String keyword,
                               @RequestParam(name="page", defaultValue = "0") int page,
                               @RequestParam(name="pageSize", defaultValue = "12") int pageSize) {
        workspaces.requireAvailable(request, "business");
        if (!canReadKnowledge(request)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "知识库访问未授权");
        }
        String tenant = access.tenant(request), user = access.user(request);
        var categories = search.listLibrary("all", null, 1, 1,
            SearchPermissionContext.of(tenant, user, identities.authorizationRoleKeys(user))).categories();
        var candidates = categories.stream().map(c -> key(c.name())).collect(Collectors.toSet());
        var allowed = authorization.explicitlyAllowedIds(ResourceAuthorizationPort.KNOWLEDGE_BASE,
            tenant, user, Set.of(), candidates);
        var entries = categories.stream().filter(c -> allowed.contains(key(c.name())))
            .filter(c -> key(c.name()).contains(key(keyword)))
            .map(c -> new KnowledgeBase(key(c.name()), c.name(), c.count()))
            .sorted(Comparator.comparing(KnowledgeBase::name)).toList();
        int size = Math.max(1, Math.min(48, pageSize)), index = Math.max(0, page);
        return ApiResponse.success(Map.of("items", entries.stream().skip((long) index * size).limit(size).toList(),
            "page", index, "totalPages", (entries.size() + size - 1) / size));
    }

    /** Resolve references under the authenticated caller and the selected Agent's current bindings. */
    public void apply(HttpServletRequest servlet, AgentTaskSubmitRequest request, String agentId) {
        var input = request.getToolInput();
        if (input == null || !input.containsKey("businessReferences")) return;
        if (!"business".equals(servlet.getHeader(WorkspaceOriginService.HEADER))) {
            throw new IllegalArgumentException("业务资料只能在业务工作空间使用");
        }
        workspaces.requireAvailable(servlet, "business");
        if (!(input.get("businessReferences") instanceof Map<?, ?> references)) {
            throw new IllegalArgumentException("业务资料格式无效");
        }
        Set<String> skillIds = ids(references.get("skills"));
        Set<String> baseIds = ids(references.get("knowledgeBases"));
        if (skillIds.isEmpty() && baseIds.isEmpty()) return;
        if (agentId == null || !agents.isPublished(agentId) || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("请选择已发布的助手并输入问题");
        }
        String tenant = access.tenant(servlet), user = access.user(servlet);
        var agent = agents.resolve(agentId);
        Object boundSkills = agent.workflowConfig() == null ? null : agent.workflowConfig().get("boundDomainSkillIds");
        var allowedSkills = authorization.allowedIdsForAgent(ResourceAuthorizationPort.SKILL, tenant, user,
            Set.of(), skillIds, agentId);
        List<String> selectedSkills = skillIds.isEmpty() ? List.of() : skills.publishedOptions(tenant).stream()
            .filter(s -> skillIds.contains(s.getId()) && allowedSkills.contains(s.getId()) && !s.isPublicationDirty())
            .filter(s -> boundSkills instanceof Collection<?> bound && bound.contains(s.getId()))
            .map(s -> printable(s.getName()) + " (" + printable(s.getId()) + ")").toList();
        if (selectedSkills.size() != skillIds.size()) throw new IllegalArgumentException("所选技能未授权给当前助手或已下架");
        if (!baseIds.isEmpty()) {
            if (!canReadKnowledge(servlet)) throw new IllegalArgumentException("知识库访问未授权");
            var bound = scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(tenant, agentId).stream()
                .filter(s -> s.isEnabled() && "KNOWLEDGE_BASE".equals(s.getResourceType()))
                .map(s -> key(s.getResourceId())).collect(Collectors.toSet());
            if (!bound.containsAll(baseIds) || !authorization.explicitlyAllowedIdsForAgent(ResourceAuthorizationPort.KNOWLEDGE_BASE,
                tenant, user, Set.of(), baseIds, agentId).containsAll(baseIds)) {
                throw new IllegalArgumentException("所选知识库未绑定给当前助手或已失去访问权限");
            }
        }
        var context = new StringJoiner("\n", "\n\n本次用户选定的参考资料\n", "");
        if (!selectedSkills.isEmpty()) context.add("优先使用已选技能：" + String.join("、", selectedSkills));
        if (!baseIds.isEmpty()) {
            context.add("优先通过授权知识检索参考：" + baseIds.stream().map(BusinessChatResources::printable).collect(Collectors.joining("、")));
            addKnowledgeMatches(context, baseIds, tenant, user, agentId, request.getQuery());
        }
        request.setQuery(request.getQuery() + context);
    }

    private void addKnowledgeMatches(StringJoiner context, Set<String> bases, String tenant, String user,
                                     String agent, String question) {
        var effective = executionScope.resolve(tenant, user, agent, List.of(), List.of());
        if (!effective.skillAllowed() || effective.documentScopeDenied() || effective.documentIds().isEmpty()) return;
        var permitted = Set.copyOf(effective.documentIds());
        var permissions = SearchPermissionContext.of(tenant, user, effective.roles()).withAgentId(agent);
        for (String base : bases) {
            var results = search.search(question.substring(0, Math.min(question.length(), 512)),
                base, null, null, String.join(",", effective.documentIds()), 1, 2, permissions).results();
            results.stream().filter(item -> permitted.contains(item.docId())).forEach(item -> {
                String summary = printable(Objects.toString(item.summary(), ""));
                if (!summary.isBlank()) context.add("知识库匹配摘要（引用资料）《"
                    + printable(Objects.toString(item.title(), item.docId())) + "》："
                    + summary.substring(0, Math.min(summary.length(), 700)));
            });
        }
    }

    private boolean canReadKnowledge(HttpServletRequest request) {
        return workspaces.permissions(request).contains("workspace:business:knowledge");
    }

    private static Set<String> ids(Object value) {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> items) || items.size() > 8
            || items.stream().anyMatch(item -> !(item instanceof String id) || id.isBlank() || id.length() > 128
                || id.codePoints().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("业务资料选择无效");
        return items.stream().map(String.class::cast).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String key(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String printable(String value) { return value.replaceAll("[\\p{Cntrl}]", " "); }
}
