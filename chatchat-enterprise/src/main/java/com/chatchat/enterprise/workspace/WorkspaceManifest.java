package com.chatchat.enterprise.workspace;

import java.util.List;
import java.util.Set;

/** Application presentation declarations only; this document never grants resource access. */
public record WorkspaceManifest(String schemaVersion, String workspaceId, String name, String icon,
    String description, String appVersion, String configVersion, String entryRoute, String layout,
    List<Page> pages, List<String> requiredCapabilities, List<String> defaultSkills) {
    public record Page(String pageId, String viewId, String title, String route, String menuCode) {}
    public static final Set<String> VIEWS = Set.of("chat", "search", "domainAnalysis", "market", "favorites", "library", "mcp",
        "dataScience", "dataScienceEnvironment", "dataScienceDevelop", "dataScienceData", "dataScienceScripts", "dataScienceSkills",
        "agents", "schedules", "runtime", "rules", "debugger", "tasks", "system", "systemUsers", "systemOrganizations",
        "systemRoles", "systemLogins", "systemResources", "systemSkillRoles", "models");
    public WorkspaceManifest filtered(List<Page> authorized) {
        String entry = authorized.stream().anyMatch(p -> ("#/w/" + workspaceId + "/" + p.route()).equals(entryRoute))
            ? entryRoute : authorized.isEmpty() ? "" : "#/w/" + workspaceId + "/" + authorized.get(0).route();
        return new WorkspaceManifest(schemaVersion, workspaceId, name, icon, description, appVersion, configVersion,
            entry, layout, authorized, requiredCapabilities, List.of());
    }
    public void validate() {
        if (!"workspace-manifest.v1".equals(schemaVersion) || workspaceId == null || !workspaceId.matches("[a-z][a-z0-9-]{0,63}")
            || name == null || name.isBlank() || name.length() > 128 || !Set.of("default", "studio").contains(layout)
            || pages == null || pages.isEmpty() || pages.size() > 64 || requiredCapabilities == null || defaultSkills == null
            || defaultSkills.size() > 32 || defaultSkills.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 128)
            || appVersion == null || configVersion == null) {
            throw new IllegalArgumentException("Invalid Workspace manifest");
        }
        Set<String> ids = new java.util.HashSet<>(); Set<String> routes = new java.util.HashSet<>();
        for (Page page : pages) {
            if (page == null || page.pageId() == null || !page.pageId().matches("[a-zA-Z][a-zA-Z0-9-]{0,63}")
                || !ids.add(page.pageId()) || !VIEWS.contains(page.viewId()) || page.route() == null
                || !page.route().matches("[a-zA-Z][a-zA-Z0-9/-]{0,127}") || page.route().contains("//")
                || !routes.add(page.route()) || page.menuCode() == null || page.menuCode().isBlank())
                throw new IllegalArgumentException("Invalid or duplicate Workspace page");
        }
        if (pages.stream().noneMatch(p -> ("#/w/" + workspaceId + "/" + p.route()).equals(entryRoute)))
            throw new IllegalArgumentException("Workspace entry must reference a registered page");
    }
}
