package com.chatchat.chat.skills.runtime;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.api.skill.SkillRequirements;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.port.outbound.SkillPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Database relationship model is the sole authority for Skill and resource access. */
@Component
@RequiredArgsConstructor
public class DatabaseRuntimeSkillPolicy implements SkillPolicy {
    private final ResourceAuthorizationPort authorization;
    private final SkillResourceScopeRepository scopes;

    @Override
    @Transactional(readOnly = true)
    public boolean canDiscover(SkillRoleContext context, SkillDescriptor descriptor) {
        return explicitlyAllowed(ResourceAuthorizationPort.SKILL, context, Set.of(descriptor.id()))
            .contains(descriptor.id());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizedSkillScope authorize(SkillRoleContext context, ResolvedSkill skill) {
        if (!canDiscover(context, skill.descriptor())) return AuthorizedSkillScope.denied("SKILL_NOT_GRANTED");
        List<SkillResourceScope> bindings = scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(
            context.tenantId(), skill.descriptor().id());
        Set<String> documents = ids(bindings, "DOCUMENT");
        Set<String> knowledgeBases = ids(bindings, "KNOWLEDGE_BASE");
        Set<String> mcpTools = ids(bindings, "MCP_TOOL");
        Set<String> agents = ids(bindings, "AGENT");
        Set<String> workflows = ids(bindings, "WORKFLOW");
        SkillRequirements declared = skill.requirements();
        documents = requestedIntersection(documents, declared.documentIds());
        knowledgeBases = requestedIntersection(knowledgeBases, declared.knowledgeBaseIds());
        mcpTools = requestedIntersection(mcpTools, declared.mcpToolIds());
        agents = requestedIntersection(agents, declared.agentIds());
        workflows = requestedIntersection(workflows, declared.workflowIds());
        return new AuthorizedSkillScope(true,
            List.copyOf(explicitlyAllowed(ResourceAuthorizationPort.KNOWLEDGE, context, documents)),
            List.copyOf(explicitlyAllowed(ResourceAuthorizationPort.KNOWLEDGE_BASE, context, knowledgeBases)),
            List.copyOf(explicitlyAllowed(ResourceAuthorizationPort.MCP_TOOL, context, mcpTools)),
            List.copyOf(explicitlyAllowed(ResourceAuthorizationPort.AGENT_SKILL, context, agents)),
            List.copyOf(explicitlyAllowed(ResourceAuthorizationPort.WORKFLOW, context, workflows)),
            List.of());
    }

    private Set<String> ids(List<SkillResourceScope> bindings, String type) {
        Set<String> values = new LinkedHashSet<>();
        if (bindings == null) return values;
        bindings.stream().filter(SkillResourceScope::isEnabled)
            .filter(binding -> type.equalsIgnoreCase(binding.getResourceType()))
            .map(SkillResourceScope::getResourceId).filter(id -> id != null && !id.isBlank())
            .map(String::trim).forEach(values::add);
        return values;
    }

    private Set<String> requestedIntersection(Set<String> databaseBindings, List<String> declarations) {
        if (declarations == null || declarations.isEmpty()) return databaseBindings;
        Set<String> requested = declarations.stream().map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        databaseBindings.removeIf(value -> !requested.contains(value.toLowerCase(Locale.ROOT)));
        return databaseBindings;
    }

    private Set<String> explicitlyAllowed(String type, SkillRoleContext context, Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Set.of();
        Object agentId = context.attributes().get("agentId");
        if (agentId == null) return authorization.explicitlyAllowedIds(type, context.tenantId(), context.userId(),
            new LinkedHashSet<>(context.roleIds()), candidates);
        return authorization.explicitlyAllowedIdsForAgent(type, context.tenantId(), context.userId(),
            new LinkedHashSet<>(context.roleIds()), candidates, String.valueOf(agentId));
    }
}
