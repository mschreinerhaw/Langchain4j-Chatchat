package com.chatchat.enterprise.workspace;

import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.service.EnterpriseAdminService.MenuNode;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.identity.SysMenuRepository;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkspaceService {
    private final WorkspaceRepository workspaces;
    private final WorkspacePreferenceRepository preferences;
    private final SysTenantRepository tenants;
    private final SysMenuRepository menus;
    private final SysPermissionRepository permissions;
    private final EnterpriseAdminService admin;
    private final SysAuditLogRepository audit;
    private final ObjectMapper mapper;
    private final com.chatchat.common.retrieval.ResourceAuthorizationPort resourceAuthorization;

    public static class AccessDenied extends RuntimeException { public AccessDenied() { super("Workspace unavailable or unauthorized"); } }
    public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    public record View(WorkspaceManifest manifest, boolean enabled, boolean defaultWorkspace, long revision, String accessPermissionCode) {}
    public record PreferenceView(String defaultWorkspaceId, String lastWorkspaceId, String effectiveWorkspaceId, long revision) {}
    public record Update(WorkspaceManifest manifest, boolean enabled, boolean defaultWorkspace, String accessPermissionCode, long expectedRevision) {}

    private EnterpriseAdminService.UserView identity(String userId) {
        var user = admin.getUserView(userId);
        if (!"enabled".equalsIgnoreCase(user.status()) || user.tenantId() == null) throw new AccessDenied();
        return user;
    }
    private void lockTenant(String tenant) {
        var row = tenants.lockById(tenant).orElseThrow(AccessDenied::new);
        if (!"enabled".equalsIgnoreCase(row.getStatus())) throw new AccessDenied();
    }
    private String json(WorkspaceManifest manifest) {
        try { return mapper.writeValueAsString(manifest); } catch (Exception e) { throw new IllegalArgumentException("Invalid manifest", e); }
    }
    private WorkspaceManifest manifest(WorkspaceEntity entity) {
        try { var m = mapper.readValue(entity.getManifestJson(), WorkspaceManifest.class); m.validate(); return m; }
        catch (Exception e) { throw new IllegalStateException("Invalid stored workspace manifest: " + entity.getWorkspaceId(), e); }
    }
    /** Serialized per tenant; never overwrites a configured or disabled record. */
    private void ensureBuiltins(String tenant) {
        lockTenant(tenant);
        if (workspaces.findByTenantIdAndWorkspaceId(tenant, "zhice").isEmpty()) {
            var pages = WorkspaceManifest.VIEWS.stream().sorted().map(v -> new WorkspaceManifest.Page(v, v, v, v, v)).toList();
            seed(tenant, new WorkspaceManifest("workspace-manifest.v1", "zhice", "灵动智策", "layout-dashboard",
                "智能对话、资源与系统管理", "1.0.0", "1", "#/w/zhice/chat", "default", pages, List.of(), List.of()), null, true, 0);
        }
        if (workspaces.findByTenantIdAndWorkspaceId(tenant, "data-science").isEmpty()) {
            var pages = List.of(new WorkspaceManifest.Page("environment", "dataScienceEnvironment", "Python 环境", "python/environment", "dataScienceEnvironment"),
                new WorkspaceManifest.Page("develop", "dataScienceDevelop", "Python 开发 / AI 编程助手", "python/develop", "dataScienceDevelop"),
                new WorkspaceManifest.Page("data", "dataScienceData", "我的数据", "python/data", "dataScienceData"),
                new WorkspaceManifest.Page("scripts", "dataScienceScripts", "我的脚本", "python/scripts", "dataScienceScripts"),
                new WorkspaceManifest.Page("skills", "dataScienceSkills", "领域 Skills", "skills", "dataScienceSkills"));
            seed(tenant, new WorkspaceManifest("workspace-manifest.v1", "data-science", "数据科学", "code",
                "Python Studio；SQL Studio 预留，尚未实现", "1.0.0", "1", "#/w/data-science/python/environment", "studio",
                pages, List.of("capability:data-science"), List.of()), "capability:data-science", false, 10);
        }
    }
    private void seed(String tenant, WorkspaceManifest manifest, String permission, boolean defaultWorkspace, int order) {
        var row = new WorkspaceEntity(); row.setTenantId(tenant); row.setWorkspaceId(manifest.workspaceId());
        row.setManifestJson(json(manifest)); row.setAccessPermissionCode(permission); row.setDefaultWorkspace(defaultWorkspace);
        row.setSortOrder(order); workspaces.save(row);
    }
    private Map<String, MenuNode> authorizedMenus(String user) {
        Map<String, MenuNode> result = new LinkedHashMap<>(); collect(admin.listAuthorizedMenus(user), result); return result;
    }
    private void collect(List<MenuNode> nodes, Map<String, MenuNode> result) {
        for (var node : nodes) { result.put(node.id(), node); if (node.children() != null) collect(node.children(), result); }
    }
    private WorkspaceManifest filtered(WorkspaceEntity row, EnterpriseAdminService.UserView user, Map<String, MenuNode> menuMap) {
        if (!row.isEnabled() || (row.getAccessPermissionCode() != null && !row.getAccessPermissionCode().isBlank()
            && !user.permissionCodes().contains(row.getAccessPermissionCode()))) return null;
        var m = manifest(row);
        if (!user.permissionCodes().containsAll(m.requiredCapabilities())) return null;
        var pages = m.pages().stream().filter(p -> menuMap.containsKey(p.menuCode()) && p.viewId().equals(p.menuCode())).map(p ->
            new WorkspaceManifest.Page(p.pageId(), p.viewId(), menuMap.get(p.menuCode()).title(), p.route(), p.menuCode())).toList();
        if (pages.isEmpty()) return null;
        var result = m.filtered(pages);
        var skills = resourceAuthorization.explicitlyAllowedIds(com.chatchat.common.retrieval.ResourceAuthorizationPort.SKILL,
            user.tenantId(), user.id(), Set.of(), new HashSet<>(m.defaultSkills()));
        return new WorkspaceManifest(result.schemaVersion(), result.workspaceId(), result.name(), result.icon(), result.description(),
            result.appVersion(), result.configVersion(), result.entryRoute(), result.layout(), result.pages(), result.requiredCapabilities(), List.copyOf(skills));
    }
    @Transactional
    public List<View> available(String userId) {
        var user = identity(userId); ensureBuiltins(user.tenantId()); var menuMap = authorizedMenus(userId);
        return workspaces.findByTenantIdOrderBySortOrderAscWorkspaceIdAsc(user.tenantId()).stream().map(row -> {
            var filtered = filtered(row, user, menuMap);
            return filtered == null ? null : new View(filtered, true, row.isDefaultWorkspace(), row.getRevision(), null);
        }).filter(Objects::nonNull).toList();
    }
    @Transactional
    public WorkspaceManifest accessible(String userId, String workspaceId) {
        return available(userId).stream().filter(v -> v.manifest().workspaceId().equals(workspaceId)).map(View::manifest)
            .findFirst().orElseThrow(AccessDenied::new);
    }
    @Transactional
    public List<View> all(String userId) {
        var user = requireManager(userId); ensureBuiltins(user.tenantId());
        return workspaces.findByTenantIdOrderBySortOrderAscWorkspaceIdAsc(user.tenantId()).stream()
            .map(r -> new View(manifest(r), r.isEnabled(), r.isDefaultWorkspace(), r.getRevision(), r.getAccessPermissionCode())).toList();
    }
    private EnterpriseAdminService.UserView requireManager(String id) {
        var user = identity(id); if (!user.permissionCodes().contains("system:admin:operate")) throw new AccessDenied(); return user;
    }
    @Transactional
    public View save(String userId, String workspaceId, Update update, boolean create) {
        var user = requireManager(userId); ensureBuiltins(user.tenantId());
        if (update == null || update.manifest() == null) throw new IllegalArgumentException("Manifest required");
        var m = update.manifest(); m.validate();
        if (!Objects.equals(workspaceId, m.workspaceId())) throw new IllegalArgumentException("Workspace ID is immutable");
        if (!"zhice".equals(workspaceId) && (update.accessPermissionCode() == null || update.accessPermissionCode().isBlank()))
            throw new IllegalArgumentException("An existing access permission is required");
        if (update.accessPermissionCode() != null && !update.accessPermissionCode().isBlank()
            && permissions.findByPermissionCode(update.accessPermissionCode()).isEmpty()) throw new IllegalArgumentException("Unknown access permission");
        for (String capability : m.requiredCapabilities()) if (permissions.findByPermissionCode(capability).isEmpty())
            throw new IllegalArgumentException("Unknown feature capability permission: " + capability);
        Set<String> configuredMenuCodes = new HashSet<>(); menus.findAll().forEach(menu -> configuredMenuCodes.add(menu.getMenuCode()));
        if (m.pages().stream().anyMatch(p -> !p.viewId().equals(p.menuCode()))) throw new IllegalArgumentException("Page must inherit its existing menu permission");
        if (!"zhice".equals(workspaceId) && m.pages().stream().anyMatch(p -> !configuredMenuCodes.contains(p.menuCode())))
            throw new IllegalArgumentException("Unknown menu reference");
        var existing = workspaces.findByTenantIdAndWorkspaceId(user.tenantId(), workspaceId);
        if (create && existing.isPresent()) throw new Conflict("Workspace already registered");
        if (!create && existing.isEmpty()) throw new AccessDenied();
        var row = existing.orElseGet(WorkspaceEntity::new);
        if (!create && row.getRevision() != update.expectedRevision()) throw new Conflict("Workspace revision changed");
        if (row.isDefaultWorkspace() && (!update.enabled() || !update.defaultWorkspace()))
            throw new Conflict("Set an enabled replacement default before disabling the current default");
        if (update.defaultWorkspace() && !update.enabled()) throw new IllegalArgumentException("Default workspace must be enabled");
        if (update.defaultWorkspace()) for (var other : workspaces.findByTenantIdOrderBySortOrderAscWorkspaceIdAsc(user.tenantId())) {
            if (!other.getWorkspaceId().equals(workspaceId) && other.isDefaultWorkspace()) { other.setDefaultWorkspace(false); workspaces.save(other); }
        }
        row.setTenantId(user.tenantId()); row.setWorkspaceId(workspaceId); row.setManifestJson(json(m));
        row.setAccessPermissionCode(update.accessPermissionCode()); row.setEnabled(update.enabled()); row.setDefaultWorkspace(update.defaultWorkspace());
        workspaces.saveAndFlush(row);
        var log = new SysAuditLog(); log.setTenantId(user.tenantId()); log.setActorId(user.id()); log.setActorName(user.displayName());
        log.setModuleName("workspace"); log.setActionName(create ? "register" : "update"); log.setResourceType("workspace");
        log.setResourceId(workspaceId); log.setDetail("revision=" + row.getRevision() + ",enabled=" + row.isEnabled()); audit.save(log);
        return new View(m, row.isEnabled(), row.isDefaultWorkspace(), row.getRevision(), row.getAccessPermissionCode());
    }
    @Transactional
    public PreferenceView preference(String userId) {
        var user = identity(userId); var available = available(userId);
        var row = preferences.findByTenantIdAndUserId(user.tenantId(), user.id()).orElse(null);
        Set<String> allowed = new HashSet<>(); available.forEach(v -> allowed.add(v.manifest().workspaceId()));
        String ownDefault = row != null && allowed.contains(row.getDefaultWorkspaceId()) ? row.getDefaultWorkspaceId() : null;
        String last = row != null && allowed.contains(row.getLastWorkspaceId()) ? row.getLastWorkspaceId() : null;
        String fallback = available.stream().filter(View::defaultWorkspace).findFirst().or(() -> available.stream().findFirst())
            .map(v -> v.manifest().workspaceId()).orElse(null);
        return new PreferenceView(ownDefault, last, last != null ? last : ownDefault != null ? ownDefault : fallback, row == null ? 0 : row.getRevision() + 1);
    }
    @Transactional
    public PreferenceView setPreference(String userId, String defaultId, String lastId, long expectedRevision) {
        var user = identity(userId); lockTenant(user.tenantId());
        if (defaultId != null) accessible(userId, defaultId); if (lastId != null) accessible(userId, lastId);
        var row = preferences.findByTenantIdAndUserId(user.tenantId(), user.id()).orElseGet(WorkspacePreference::new);
        if ((row.getId() == null ? 0 : row.getRevision() + 1) != expectedRevision) throw new Conflict("Preference revision changed");
        row.setTenantId(user.tenantId()); row.setUserId(user.id()); row.setDefaultWorkspaceId(defaultId); row.setLastWorkspaceId(lastId);
        preferences.saveAndFlush(row); return preference(userId);
    }
}
