package com.chatchat.chat.skills.runtime;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Intersects the Agent Skill's document requirements with canonical user and role grants. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseSkillExecutionScopeService implements SkillExecutionScopePort {
    private static final int TAG_PAGE_SIZE = 500;
    private final SkillResourceScopeRepository scopes;
    private final SysUserRepository users;
    private final SysUserRoleRepository userRoles;
    private final SysRoleRepository roles;
    private final KnowledgeIRRepository knowledgeUnits;
    private final ResourceAuthorizationPort authorization;
    private final ObjectMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public EffectiveScope resolve(String tenantId, String userId, String skillId,
                                  List<String> legacyDocumentIds, List<String> legacyTags) {
        SysUser user = userId == null ? null : users.findById(userId).orElse(null);
        if (tenantId == null || user == null || !tenantId.equals(user.getTenantId())
            || !"enabled".equalsIgnoreCase(user.getStatus())) return EffectiveScope.denied(List.of());
        Set<String> assigned = new HashSet<>();
        userRoles.findByUserId(userId).stream()
            .filter(binding -> tenantId.equals(binding.getTenantId()))
            .forEach(binding -> assigned.add(binding.getRoleId()));
        Set<String> roleIds = new LinkedHashSet<>();
        Set<String> nativeRoles = new LinkedHashSet<>();
        for (SysRole role : assigned.isEmpty() ? List.<SysRole>of()
            : roles.findByTenantIdAndIdIn(tenantId, assigned)) {
            if (!assigned.contains(role.getId()) || !"enabled".equalsIgnoreCase(role.getStatus())) continue;
            roleIds.add(role.getId());
            nativeRoles.add(role.getId());
            if (role.getRoleCode() != null) nativeRoles.add(role.getRoleCode());
            if (role.getRoleName() != null) nativeRoles.add(role.getRoleName());
        }
        List<String> roleNames = List.copyOf(nativeRoles);
        boolean skillGranted = skillId != null && !skillId.isBlank()
            && authorization.explicitlyAllowedIds(ResourceAuthorizationPort.AGENT_SKILL,
                tenantId, userId, roleIds, Set.of(skillId)).contains(skillId);
        if (!skillGranted) {
            log.warn("skill_execution_scope_denied tenantId={} userId={} skillId={} reason=agent_skill_not_granted",
                tenantId, userId, skillId);
            return EffectiveScope.denied(roleNames);
        }

        List<SkillResourceScope> configured = scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(
            tenantId, skillId);
        boolean managed = true;
        Set<String> directIds = new LinkedHashSet<>();
        Set<String> baseIds = new LinkedHashSet<>();
        for (SkillResourceScope binding : configured) {
            if (!binding.isEnabled()) continue;
            if ("DOCUMENT".equals(binding.getResourceType())) directIds.add(binding.getResourceId());
            if ("KNOWLEDGE_BASE".equals(binding.getResourceType()))
                baseIds.add(binding.getResourceId().toLowerCase(Locale.ROOT));
        }
        boolean agentScopeConfigured = !directIds.isEmpty() || !baseIds.isEmpty();

        // No Agent binding means no knowledge retrieval. Role-level access only limits
        // an Agent's configured resources; it must never become an implicit binding to
        // every document the caller can read.
        if (!agentScopeConfigured) {
            log.info("skill_execution_scope_resolved tenantId={} userId={} skillId={} documents=0 "
                    + "directBindings=0 knowledgeBaseBindings=0 agentScopeConfigured=false",
                tenantId, userId, skillId);
            return new EffectiveScope(List.of(), List.of(), roleNames, true, true);
        }

        Map<String, Set<String>> documentBases = new HashMap<>();
        if (!directIds.isEmpty()) {
            for (KnowledgeIREntity unit : knowledgeUnits.findByDocumentIdInAndActiveTrue(new ArrayList<>(directIds))) {
                if (tenantId.equals(unit.getTenantId())) addBases(documentBases, unit);
            }
        }
        for (String base : baseIds) {
            String pattern = "%" + base.toLowerCase(Locale.ROOT) + "%";
            for (int page = 0; ; page++) {
                List<KnowledgeIREntity> units = knowledgeUnits.findByTenantAndTagPattern(
                    tenantId, pattern, PageRequest.of(page, TAG_PAGE_SIZE));
                for (KnowledgeIREntity unit : units) {
                    Set<String> tags = tags(unit);
                    if (tags.stream().anyMatch(tag -> tag.equalsIgnoreCase(base))) addBases(documentBases, unit);
                }
                if (units.size() < TAG_PAGE_SIZE) break;
            }
        }
        Set<String> candidates = new LinkedHashSet<>(directIds);
        candidates.addAll(documentBases.keySet());
        Set<String> allowedDocuments = authorization.allowedIds(ResourceAuthorizationPort.KNOWLEDGE,
            tenantId, userId, roleIds, candidates);
        Set<String> explicitDocuments = authorization.explicitlyAllowedIds(ResourceAuthorizationPort.KNOWLEDGE,
            tenantId, userId, roleIds, candidates);
        Set<String> categories = new LinkedHashSet<>();
        documentBases.values().forEach(categories::addAll);
        Set<String> explicitBases = authorization.explicitlyAllowedIds(ResourceAuthorizationPort.KNOWLEDGE_BASE,
            tenantId, userId, roleIds, categories);
        Set<String> selected = new LinkedHashSet<>();
        for (String docId : candidates) {
            if (!allowedDocuments.contains(docId)) continue;
            if (!explicitDocuments.contains(docId)
                && documentBases.getOrDefault(docId, Set.of()).stream().noneMatch(explicitBases::contains)) continue;
            selected.add(docId);
        }
        if (selected.isEmpty()) {
            log.warn("skill_execution_scope_denied tenantId={} userId={} skillId={} "
                    + "reason=resource_grant_intersection_empty candidates={} allowedDocuments={} "
                    + "explicitDocuments={} explicitKnowledgeBases={}",
                tenantId, userId, skillId, candidates.size(), allowedDocuments.size(),
                explicitDocuments.size(), explicitBases.size());
        } else {
            log.info("skill_execution_scope_resolved tenantId={} userId={} skillId={} documents={} "
                    + "directBindings={} knowledgeBaseBindings={} agentScopeConfigured={}",
                tenantId, userId, skillId, selected.size(), directIds.size(), baseIds.size(),
                agentScopeConfigured);
        }
        return new EffectiveScope(selected.isEmpty() ? List.of(DENIED_DOCUMENT_ID) : List.copyOf(selected),
            List.of(), roleNames, true, true);
    }

    private void addBases(Map<String, Set<String>> documentBases, KnowledgeIREntity unit) {
        if (unit.getDocumentId() == null || unit.getDocumentId().isBlank()) return;
        documentBases.computeIfAbsent(unit.getDocumentId(), ignored -> new LinkedHashSet<>()).addAll(tags(unit));
    }

    private Set<String> tags(KnowledgeIREntity unit) {
        try {
            if (unit.getTagsJson() == null) return Set.of();
            Set<String> result = new LinkedHashSet<>();
            mapper.readValue(unit.getTagsJson(), new TypeReference<List<String>>() {})
                .forEach(tag -> { if (tag != null) result.add(tag.toLowerCase(Locale.ROOT)); });
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank())
            .map(String::trim).distinct().toList();
    }
}
