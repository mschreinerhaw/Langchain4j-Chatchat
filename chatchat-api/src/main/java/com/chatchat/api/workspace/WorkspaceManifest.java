package com.chatchat.api.workspace;

import java.util.List;
import java.util.Map;

/** Application declaration, never an executable workflow or grant. */
public record WorkspaceManifest(String schemaVersion, int configVersion, String workspaceId,
    String appVersion, String name, String icon, String description, String entryPage, String layoutType,
    List<Page> pages, List<String> requiredCapabilities, List<Capability> optionalCapabilities,
    List<String> defaultSkillIds) {
    public record Page(String id, String menuCode, String componentKey, Map<String, String> props) {}
    public record Capability(String id, String name, String state) {}
}
