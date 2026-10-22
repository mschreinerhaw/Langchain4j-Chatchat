package com.chatchat.mcpserver.license;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.license.LicenseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/license")
public class LicenseInternalController {

    private final McpLicenseService licenseService;

    @GetMapping("/coding-dispatch-entitlement")
    public ApiResponse<CodingDispatchEntitlement> codingDispatchEntitlement() {
        LicenseStatus status = licenseService.status();
        return ApiResponse.success(new CodingDispatchEntitlement(
            status != null && status.valid(), status == null ? "UNAVAILABLE" : status.status()));
    }

    public record CodingDispatchEntitlement(boolean licenseValid, String licenseStatus) { }

    @GetMapping("/workspace-entitlement")
    public ApiResponse<WorkspaceEntitlement> workspaceEntitlement() {
        LicenseStatus status = licenseService.status();
        if (status == null || !status.valid() || status.license() == null || status.license().modules() == null) {
            return ApiResponse.success(new WorkspaceEntitlement(false, Set.of(), status == null ? "INVALID" : status.status()));
        }
        Set<String> modules = status.license().modules().stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.toSet());
        boolean explicit = modules.stream().anyMatch(value -> value.startsWith("workspace:"))
            || modules.contains("workspacezhice") || modules.contains("workspacedatascience");
        Set<String> ids = new java.util.HashSet<>();
        modules.stream().filter(value -> value.startsWith("workspace:"))
            .map(value -> value.substring("workspace:".length()))
            .filter(value -> value.matches("[a-z][a-z0-9-]{0,63}"))
            .forEach(ids::add);
        if (modules.contains("workspacezhice")) ids.add("zhice");
        if (modules.contains("workspacedatascience")) ids.add("data-science");
        if (!explicit) { ids.add("zhice"); ids.add("data-science"); }
        // Governance is part of the existing platform workspace entitlement, not a separate data capability.
        if (ids.contains("zhice")) { ids.add("permissions"); ids.add("mcp-publication"); }
        return ApiResponse.success(new WorkspaceEntitlement(true, Set.copyOf(ids), status.status()));
    }

    public record WorkspaceEntitlement(boolean licenseValid, Set<String> workspaceIds, String licenseStatus) { }

    @GetMapping("/agent-publication-limit")
    public ApiResponse<AgentPublicationLimit> agentPublicationLimit() {
        LicenseStatus status = licenseService.status();
        Integer maximum = status != null && status.license() != null ? status.license().maxAgents() : null;
        return ApiResponse.success(new AgentPublicationLimit(
            status != null && status.valid(),
            status == null ? "INVALID" : status.status(),
            status == null ? "License status unavailable" : status.message(),
            maximum,
            maximum != null
        ));
    }

    public record AgentPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                        Integer maxPublishedAgents, boolean limited) {
    }

    @GetMapping("/skill-publication-limit")
    public ApiResponse<SkillPublicationLimit> skillPublicationLimit() {
        LicenseStatus status = licenseService.status();
        Integer maximum = status != null && status.license() != null ? status.license().maxSkills() : null;
        return ApiResponse.success(new SkillPublicationLimit(
            status != null && status.valid(),
            status == null ? "INVALID" : status.status(),
            status == null ? "License status unavailable" : status.message(),
            maximum,
            maximum != null
        ));
    }

    public record SkillPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                        Integer maxPublishedSkills, boolean limited) { }

    @GetMapping("/skill-federation-entitlement")
    public ApiResponse<SkillFederationEntitlement> skillFederationEntitlement() {
        LicenseStatus status = licenseService.status();
        boolean allowed = status != null && status.valid() && licenseService.allowsModule("mcpSkillFederation");
        return ApiResponse.success(new SkillFederationEntitlement(
            allowed,
            status == null ? "INVALID" : status.status(),
            allowed ? "MCP Skills federation is licensed" : "License does not include MCP Skills federation"
        ));
    }

    public record SkillFederationEntitlement(boolean allowed, String licenseStatus, String message) { }
}
