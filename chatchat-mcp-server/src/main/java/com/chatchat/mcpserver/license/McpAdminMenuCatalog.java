package com.chatchat.mcpserver.license;

import com.chatchat.license.LicenseStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Runtime source of truth for MCP administration modules. */
@Component
public class McpAdminMenuCatalog {
    private final List<MenuDefinition> menus;

    public McpAdminMenuCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("mcp-admin-menus.json").getInputStream()) {
            menus = List.copyOf(objectMapper.readValue(input, new TypeReference<List<MenuDefinition>>() { }));
        } catch (IOException ex) {
            throw new IllegalStateException("无法加载 MCP 菜单目录", ex);
        }
        if (menus.isEmpty() || menus.stream().anyMatch(menu -> menu.key() == null || menu.key().isBlank())) {
            throw new IllegalStateException("MCP 菜单目录不能为空且每个菜单必须具有稳定 ID");
        }
    }

    public List<MenuDefinition> menus() { return menus; }

    public List<MenuDefinition> navigationMenus() {
        return menus.stream().filter(MenuDefinition::navigation).toList();
    }

    /** Modules offered to new License issuers; aggregate parents are derived from selected children. */
    public List<MenuDefinition> licenseModules() {
        var aggregateParents = menus.stream()
            .map(MenuDefinition::parentKey)
            .filter(value -> value != null && !value.isBlank())
            .map(McpAdminMenuCatalog::normalize)
            .collect(java.util.stream.Collectors.toSet());
        return menus.stream().filter(menu -> !aggregateParents.contains(normalize(menu.key()))).toList();
    }

    public Optional<MenuDefinition> menuForPath(String path) {
        if (path == null) return Optional.empty();
        return menus.stream()
            .filter(menu -> menu.apiPrefixes() != null)
            .flatMap(menu -> menu.apiPrefixes().stream()
                .filter(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))
                .map(prefix -> new PathMatch(menu, prefix.length())))
            .max(java.util.Comparator.comparingInt(PathMatch::prefixLength))
            .map(PathMatch::menu);
    }

    public boolean authorized(LicenseStatus status, String menuKey) {
        if (status == null || !status.valid() || status.license() == null || status.license().modules() == null) {
            return false;
        }
        var licensedModules = status.license().modules().stream()
            .map(McpAdminMenuCatalog::normalize)
            .collect(java.util.stream.Collectors.toSet());
        if (licensedModules.contains("mcp") || licensedModules.contains(normalize(menuKey))) {
            return true;
        }
        return menus.stream()
            .filter(menu -> normalize(menu.key()).equals(normalize(menuKey)))
            .flatMap(menu -> menu.impliedBy() == null ? java.util.stream.Stream.empty() : menu.impliedBy().stream())
            .map(McpAdminMenuCatalog::normalize)
            .anyMatch(licensedModules::contains);
    }

    public List<MenuAccess> access(LicenseStatus status) {
        return navigationMenus().stream()
            .map(menu -> new MenuAccess(menu.key(), menu.label(), menu.icon(), navigationAuthorized(status, menu)))
            .toList();
    }

    public Optional<MenuDefinition> moduleForTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return Optional.empty();
        return menus.stream()
            .flatMap(menu -> menu.toolPatterns() == null ? java.util.stream.Stream.empty()
                : menu.toolPatterns().stream().filter(pattern -> matchesTool(pattern, toolName))
                    .map(pattern -> new ToolMatch(menu, pattern.replace("*", "").length())))
            .max(java.util.Comparator.comparingInt(ToolMatch::specificity))
            .map(ToolMatch::menu);
    }

    private boolean navigationAuthorized(LicenseStatus status, MenuDefinition menu) {
        if (authorized(status, menu.key())) return true;
        return menus.stream()
            .filter(candidate -> normalize(menu.key()).equals(normalize(candidate.parentKey())))
            .anyMatch(candidate -> authorized(status, candidate.key()));
    }

    private static boolean matchesTool(String pattern, String toolName) {
        String expected = normalize(pattern);
        String actual = normalize(toolName);
        if (expected.endsWith("*")) return actual.startsWith(expected.substring(0, expected.length() - 1));
        return expected.equals(actual);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record MenuDefinition(String key, String label, String icon, boolean navigation, String parentKey,
                                 String description, List<String> apiPrefixes, List<String> toolPatterns,
                                 List<String> impliedBy) { }
    public record MenuAccess(String key, String label, String icon, boolean authorized) { }
    private record PathMatch(MenuDefinition menu, int prefixLength) { }
    private record ToolMatch(MenuDefinition menu, int specificity) { }
}
