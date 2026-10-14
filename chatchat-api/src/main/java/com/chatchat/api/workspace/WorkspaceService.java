package com.chatchat.api.workspace;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.enterprise.entity.workspace.*;
import com.chatchat.enterprise.repository.workspace.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkspaceService {
    private final WorkspaceRegistrationRepository registry;
    private final WorkspacePreferenceRepository preferences;
    private final EnterpriseAdminService identities;
    private final ObjectAccessService access;
    private final DomainSkillRuntimePort skills;
    private final McpLicenseEntitlementPort license;
    private final ObjectMapper mapper;
    private final com.chatchat.enterprise.repository.audit.SysAuditLogRepository audits;

    public record Available(String workspaceId, String name, String icon, String description,
        String entryPage, String layoutType, boolean defaultWorkspace, int configVersion) {}
    public record Loaded(WorkspaceManifest manifest, List<EnterpriseAdminService.MenuNode> menus) {}
    public record Preference(String defaultWorkspaceId, String lastWorkspaceId) {}
    public record Registration(WorkspaceManifest manifest, String status, String accessPermissionCode,
                               boolean defaultWorkspace, Long version) {}
    public record Update(WorkspaceManifest manifest, String status, String accessPermissionCode, Long expectedVersion) {}

    @Transactional(readOnly = true)
    public List<Available> licensedCatalog() {
        var entitlement = license.workspaceEntitlement();
        return registry.findAll().stream().sorted(Comparator.comparing(WorkspaceRegistration::getId))
            .filter(row -> "enabled".equals(row.getStatus()) && entitlement.allows(row.getId()))
            .map(row -> {
                var manifest = read(row);
                return new Available(row.getId(), manifest.name(), manifest.icon(), manifest.description(),
                    manifest.entryPage(), manifest.layoutType(), row.isDefaultWorkspace(), manifest.configVersion());
            }).toList();
    }

    @Transactional(readOnly = true)
    public List<Available> available(HttpServletRequest request) {
        var permissions = permissions(request);
        var entitlement = license.workspaceEntitlement();
        return registry.findAll().stream().sorted(Comparator.comparing(WorkspaceRegistration::getId))
            .filter(row -> allowed(row, permissions) && entitlement.allows(row.getId())).map(row -> {
                var manifest = read(row);
                return new Available(row.getId(), manifest.name(), manifest.icon(), manifest.description(),
                    manifest.entryPage(), manifest.layoutType(), row.isDefaultWorkspace(), manifest.configVersion());
            }).toList();
    }
    @Transactional(readOnly = true)
    public Loaded load(HttpServletRequest request, String id) {
        var row = requireAvailable(request, id);
        var declared = read(row);
        var authorized = identities.listAuthorizedMenus(access.user(request));
        Set<String> codes = new HashSet<>(); collect(authorized, codes);
        var pages = declared.pages().stream().filter(page -> codes.contains(page.menuCode())).toList();
        Set<String> pageMenus = new HashSet<>(); pages.forEach(page -> pageMenus.add(page.menuCode()));
        var menus = project(authorized, pageMenus);
        var permittedSkills = declared.defaultSkillIds().isEmpty() ? List.<String>of() :
            skills.retrievePublished(access.tenant(request), access.user(request), List.of(), "",
                declared.defaultSkillIds()).stream().map(DomainSkillRuntimePort.DomainSkillContent::id).toList();
        String entry = pages.stream().anyMatch(page -> page.id().equals(declared.entryPage()))
            ? declared.entryPage() : pages.stream().map(WorkspaceManifest.Page::id).findFirst().orElse("");
        var projected = new WorkspaceManifest(declared.schemaVersion(), declared.configVersion(), id,
            declared.appVersion(), declared.name(), declared.icon(), declared.description(), entry,
            declared.layoutType(), pages, declared.requiredCapabilities(), declared.optionalCapabilities().stream()
                .filter(capability -> "reserved".equals(capability.state()) || permissions(request).contains(capability.id())).toList(), permittedSkills);
        return new Loaded(projected, menus);
    }
    public WorkspaceRegistration requireAvailable(HttpServletRequest request, String id) {
        var row = registry.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        if (!allowed(row, permissions(request)) || !license.workspaceEntitlement().allows(id))
            throw new SecurityException("Workspace access denied");
        return row;
    }
    private Set<String> permissions(HttpServletRequest request) {
        if (access.user(request) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated identity required");
        var user = identities.getUserView(access.user(request));
        if (!"enabled".equals(user.status()) || !Objects.equals(user.tenantId(), access.tenant(request)))
            throw new SecurityException("Invalid Workspace identity");
        return new HashSet<>(user.permissionCodes());
    }
    private boolean allowed(WorkspaceRegistration row, Set<String> permissions) {
        return "enabled".equals(row.getStatus()) && permissions.contains(row.getAccessPermissionCode())
            && permissions.containsAll(read(row).requiredCapabilities());
    }
    @Transactional(readOnly = true)
    public Preference preference(HttpServletRequest request) {
        permissions(request);
        var row = preferences.findByTenantIdAndUserId(access.tenant(request), access.user(request));
        var workspaces = available(request);
        Set<String> available = new HashSet<>(); workspaces.forEach(w -> available.add(w.workspaceId()));
        String defaultId = workspaces.stream().filter(Available::defaultWorkspace).map(Available::workspaceId).findFirst().orElse(null);
        return new Preference(row.map(WorkspacePreference::getDefaultWorkspaceId).filter(available::contains).orElse(defaultId),
            row.map(WorkspacePreference::getLastWorkspaceId).filter(available::contains).orElse(null));
    }
    @Transactional
    public Preference savePreference(HttpServletRequest request, Preference update) {
        permissions(request);
        if (update.defaultWorkspaceId() != null) requireAvailable(request, update.defaultWorkspaceId());
        if (update.lastWorkspaceId() != null) requireAvailable(request, update.lastWorkspaceId());
        var row = preferences.findByTenantIdAndUserId(access.tenant(request), access.user(request)).orElseGet(() -> {
            var created = new WorkspacePreference();
            created.setId(UUID.nameUUIDFromBytes((access.tenant(request) + ":" + access.user(request)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            created.setTenantId(access.tenant(request)); created.setUserId(access.user(request)); return created;
        });
        if (row.getDefaultWorkspaceId() == null) row.setDefaultWorkspaceId(preference(request).defaultWorkspaceId());
        if (update.defaultWorkspaceId() != null) row.setDefaultWorkspaceId(update.defaultWorkspaceId());
        if (update.lastWorkspaceId() != null) row.setLastWorkspaceId(update.lastWorkspaceId());
        preferences.saveAndFlush(row); return new Preference(row.getDefaultWorkspaceId(), row.getLastWorkspaceId());
    }
    private void requireManagement(HttpServletRequest request) {
        if (!permissions(request).contains("system:workspaces:manage")) throw new SecurityException("Workspace management denied");
    }
    @Transactional(readOnly = true)
    public List<Registration> all(HttpServletRequest request) {
        requireManagement(request);
        return registry.findAll().stream().map(row -> new Registration(read(row), row.getStatus(), row.getAccessPermissionCode(),
            row.isDefaultWorkspace(), row.getVersion())).toList();
    }
    @Transactional
    public Registration save(HttpServletRequest request, String id, Update update) {
        requireManagement(request); validate(id, update.manifest());
        if (!Set.of("enabled", "disabled").contains(update.status())) throw new IllegalArgumentException("Invalid status");
        if (identities.listPermissions().stream().noneMatch(p -> p.getPermissionCode().equals(update.accessPermissionCode())))
            throw new IllegalArgumentException("Access permission must already be registered");
        var existing = registry.findById(id);
        if (existing.isPresent() && (!Objects.equals(existing.get().getVersion(), update.expectedVersion())
            || update.manifest().configVersion() <= read(existing.get()).configVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace configuration changed");
        var row = existing.orElseGet(WorkspaceRegistration::new); row.setId(id);
        row.setStatus(update.status()); row.setAccessPermissionCode(update.accessPermissionCode());
        try { row.setManifestJson(mapper.writeValueAsString(update.manifest())); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid Manifest", e); }
        registry.saveAndFlush(row);
        audit(request, "save", id);
        return new Registration(read(row), row.getStatus(), row.getAccessPermissionCode(), row.isDefaultWorkspace(), row.getVersion());
    }
    @Transactional
    public void setDefault(HttpServletRequest request, String id) {
        requireManagement(request); requireAvailable(request, id);
        var rows = registry.findAll(); rows.forEach(row -> row.setDefaultWorkspace(row.getId().equals(id)));
        registry.saveAllAndFlush(rows);
        audit(request, "default", id);
    }
    private void audit(HttpServletRequest request, String action, String id) {
        var log = new com.chatchat.enterprise.entity.audit.SysAuditLog();
        log.setTenantId(access.tenant(request)); log.setActorId(access.user(request));
        log.setModuleName("workspace"); log.setActionName(action); log.setResourceType("workspace");
        log.setResourceId(id); audits.save(log);
    }
    private WorkspaceManifest read(WorkspaceRegistration row) {
        try { return mapper.readValue(row.getManifestJson(), WorkspaceManifest.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored Workspace Manifest: " + row.getId(), e); }
    }
    public static void validate(String id, WorkspaceManifest manifest) {
        try { validateFields(id, manifest); }
        catch (NullPointerException exception) { throw new IllegalArgumentException("Manifest fields must not be null", exception); }
    }
    private static void validateFields(String id, WorkspaceManifest manifest) {
        if (manifest == null || !id.matches("[a-z][a-z0-9-]{0,63}") || !id.equals(manifest.workspaceId())
            || !"workspace-manifest.v1".equals(manifest.schemaVersion()) || manifest.configVersion() < 1
            || manifest.name() == null || manifest.name().isBlank() || manifest.appVersion() == null
            || !Set.of("assistant", "studio").contains(manifest.layoutType()) || manifest.pages() == null
            || manifest.requiredCapabilities() == null || manifest.optionalCapabilities() == null || manifest.defaultSkillIds() == null)
            throw new IllegalArgumentException("Invalid Workspace Manifest");
        Set<String> ids = new HashSet<>();
        if (manifest.pages().size() > 100 || manifest.defaultSkillIds().size() > 32 || manifest.name().length() > 128)
            throw new IllegalArgumentException("Manifest exceeds size limits");
        for (var page : manifest.pages()) {
            if (page.id() == null || !page.id().matches("[a-zA-Z][a-zA-Z0-9-]{0,63}") || !ids.add(page.id())
                || page.componentKey() == null || !page.componentKey().matches("[a-zA-Z][a-zA-Z0-9-]{0,63}")
                || page.menuCode() == null || page.props() == null || !Set.of("initialTab").containsAll(page.props().keySet())
                || page.props().values().stream().anyMatch(value -> !Set.of("environment", "develop", "data", "scripts").contains(value)))
                throw new IllegalArgumentException("Invalid Workspace page");
        }
        if (!ids.contains(manifest.entryPage())) throw new IllegalArgumentException("Entry page is not registered");
        if (manifest.optionalCapabilities().stream().anyMatch(c -> !Set.of("reserved", "available").contains(c.state())))
            throw new IllegalArgumentException("Invalid capability state");
    }
    private static void collect(List<EnterpriseAdminService.MenuNode> nodes, Set<String> ids) {
        for (var node : nodes) { ids.add(node.id()); collect(node.children(), ids); }
    }
    private static List<EnterpriseAdminService.MenuNode> project(List<EnterpriseAdminService.MenuNode> nodes, Set<String> ids) {
        List<EnterpriseAdminService.MenuNode> result = new ArrayList<>();
        for (var node : nodes) {
            var children = project(node.children(), ids);
            if (ids.contains(node.id()) || !children.isEmpty()) result.add(new EnterpriseAdminService.MenuNode(
                node.id(), node.title(), node.path(), node.icon(), node.type(), children));
        }
        return result;
    }
}
