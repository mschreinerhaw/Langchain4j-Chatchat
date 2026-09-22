package com.chatchat.chat.skills.runtime;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.identity.SysUserRole;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseSkillExecutionScopeServiceTest {
    private final SkillResourceScopeRepository scopes = mock(SkillResourceScopeRepository.class);
    private final SysUserRepository users = mock(SysUserRepository.class);
    private final SysUserRoleRepository userRoles = mock(SysUserRoleRepository.class);
    private final SysRoleRepository roles = mock(SysRoleRepository.class);
    private final KnowledgeIRRepository units = mock(KnowledgeIRRepository.class);
    private final ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
    private final DatabaseSkillExecutionScopeService service = new DatabaseSkillExecutionScopeService(
        scopes, users, userRoles, roles, units, grants, new ObjectMapper());

    @BeforeEach
    void caller() {
        SysUser user = new SysUser();
        user.setId("user-a"); user.setTenantId("tenant-a"); user.setStatus("enabled");
        SysRole role = new SysRole();
        role.setId("role-a"); role.setTenantId("tenant-a"); role.setRoleCode("advisor");
        role.setRoleName("Advisor"); role.setStatus("enabled");
        SysUserRole membership = new SysUserRole();
        membership.setTenantId("tenant-a"); membership.setUserId("user-a"); membership.setRoleId("role-a");
        when(users.findById("user-a")).thenReturn(Optional.of(user));
        when(userRoles.findByUserId("user-a")).thenReturn(List.of(membership));
        when(roles.findByTenantIdOrderByRoleNameAsc("tenant-a")).thenReturn(List.of(role));
        when(grants.allowedIds(ResourceAuthorizationPort.AGENT_SKILL, "tenant-a", "user-a",
            Set.of("role-a"), Set.of("agent-skill"))).thenReturn(Set.of("agent-skill"));
    }

    @Test
    void intersectsSkillDocumentsWithRoleDocumentGrants() {
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-a", "agent-skill"))
            .thenReturn(List.of(binding("DOCUMENT", "doc-1"), binding("DOCUMENT", "doc-2")));
        when(units.findByDocumentIdInAndActiveTrue(anyList())).thenReturn(List.of());
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a")).thenReturn(true);
        when(grants.allowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("doc-1", "doc-2")))).thenReturn(Set.of("doc-1", "doc-2"));
        when(grants.explicitlyAllowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("doc-1", "doc-2")))).thenReturn(Set.of("doc-1"));

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill", List.of("legacy-doc"), List.of());

        assertThat(resolved.documentIds()).containsExactly("doc-1");
        assertThat(resolved.roles()).contains("advisor", "Advisor");
        assertThat(resolved.managed()).isTrue();
    }

    @Test
    void expandsKnowledgeBaseFromIrTagsThenIntersectsRoleGrant() {
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-a", "agent-skill"))
            .thenReturn(List.of(binding("KNOWLEDGE_BASE", "research")));
        KnowledgeIREntity unit = new KnowledgeIREntity();
        unit.setTenantId("tenant-a"); unit.setDocumentId("doc-research");
        unit.setTagsJson("[\"research\",\"finance\"]");
        when(units.findByTenantAndTagPattern(eq("tenant-a"), eq("%research%"),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(unit));
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.KNOWLEDGE_BASE, "tenant-a")).thenReturn(true);
        when(grants.allowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("doc-research")))).thenReturn(Set.of("doc-research"));
        when(grants.explicitlyAllowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE_BASE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("research", "finance")))).thenReturn(Set.of("research"));

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill", List.of(), List.of());

        assertThat(resolved.documentIds()).containsExactly("doc-research");
        assertThat(resolved.tags()).isEmpty();
    }

    @Test
    void emptyRoleIntersectionCannotBroadenToUnrestrictedSearch() {
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-a", "agent-skill"))
            .thenReturn(List.of(binding("DOCUMENT", "doc-1")));
        when(units.findByDocumentIdInAndActiveTrue(anyList())).thenReturn(List.of());
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a")).thenReturn(true);
        when(grants.allowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("doc-1")))).thenReturn(Set.of("doc-1"));

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill", List.of(), List.of());

        assertThat(resolved.documentIds()).containsExactly(
            com.chatchat.common.retrieval.SkillExecutionScopePort.DENIED_DOCUMENT_ID);
    }

    @Test
    void legacyAgentBindingStillReceivesRoleFiltering() {
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-a", "agent-skill"))
            .thenReturn(List.of());
        when(units.findByDocumentIdInAndActiveTrue(anyList())).thenReturn(List.of());
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a")).thenReturn(true);
        when(grants.allowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("legacy-doc")))).thenReturn(Set.of("legacy-doc"));
        when(grants.explicitlyAllowedIds(eq(ResourceAuthorizationPort.KNOWLEDGE), eq("tenant-a"), eq("user-a"),
            eq(Set.of("role-a")), eq(Set.of("legacy-doc")))).thenReturn(Set.of("legacy-doc"));

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill", List.of("legacy-doc"), List.of());

        assertThat(resolved.documentIds()).containsExactly("legacy-doc");
        assertThat(resolved.managed()).isTrue();
    }

    @Test
    void configuredSkillRoleGrantsDoNotAuthorizeUnlistedAgentSkills() {
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.AGENT_SKILL, "tenant-a")).thenReturn(true);

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill", List.of("legacy-doc"), List.of());

        assertThat(resolved.skillAllowed()).isFalse();
        assertThat(resolved.documentIds()).containsExactly(
            com.chatchat.common.retrieval.SkillExecutionScopePort.DENIED_DOCUMENT_ID);
    }

    @Test
    void legacyTagBindingRemainsAvailableBeforeRoleDocumentGrantsAreConfigured() {
        when(scopes.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-a", "agent-skill"))
            .thenReturn(List.of());

        var resolved = service.resolve("tenant-a", "user-a", "agent-skill",
            List.of(), List.of("Legacy Research"));

        assertThat(resolved.documentIds()).isEmpty();
        assertThat(resolved.tags()).containsExactly("Legacy Research");
        assertThat(resolved.managed()).isFalse();
    }

    private SkillResourceScope binding(String type, String id) {
        SkillResourceScope scope = new SkillResourceScope();
        scope.setTenantId("tenant-a"); scope.setSkillId("agent-skill");
        scope.setResourceType(type); scope.setResourceId(id); scope.setEnabled(true);
        return scope;
    }
}
