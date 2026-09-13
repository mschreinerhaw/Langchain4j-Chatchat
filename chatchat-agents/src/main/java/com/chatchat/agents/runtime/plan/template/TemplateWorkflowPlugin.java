package com.chatchat.agents.runtime.plan.template;

import com.chatchat.common.tool.ToolWorkflowRole;

import java.util.Map;
import java.util.Set;

/**
 * Pluggable, asset-specific template workflow contract.
 *
 * <p>The model supplies business arguments. A matching plugin owns the invariant workflow
 * topology and the runtime binding from template discovery to execution.</p>
 */
public interface TemplateWorkflowPlugin {

    String id();

    /** Higher values win when an application intentionally overrides a built-in plugin. */
    default int priority() {
        return 0;
    }

    /** Selects the plugin from publisher-owned metadata, never from a tool-name convention. */
    boolean supports(TemplateWorkflowTool tool);

    /** Whether this tool can participate in this plugin's workflow for the declared role. */
    default boolean accepts(TemplateWorkflowTool tool) {
        return tool != null && tool.role() != null && switch (tool.role()) {
            case ASSET_DISCOVERY, TEMPLATE_DISCOVERY, TEMPLATE_EXECUTION -> supports(tool);
            case DIRECT -> false;
        };
    }

    /** Relational match used for discovery/asset nodes of one selected execution workflow. */
    default boolean accepts(TemplateWorkflowTool candidate, TemplateWorkflowTool executionTool) {
        return accepts(candidate);
    }

    /**
     * Matches either the executor-declared parent discovery tool or one of its
     * dynamically published, fixed template-group children. The child name is
     * already an authoritative group selection; Runtime must keep it and invoke
     * its parent bridge instead of widening the query back to the parent scope.
     */
    default boolean acceptsDiscoveryRelation(TemplateWorkflowTool candidate,
                                             TemplateWorkflowTool executionTool,
                                             String declaredParentToolName) {
        if (!accepts(candidate, executionTool) || declaredParentToolName == null
            || declaredParentToolName.isBlank()) {
            return false;
        }
        return sameTool(candidate.toolName(), declaredParentToolName)
            || (candidate.groupedTemplateDiscovery()
                && sameTool(candidate.parentToolName(), declaredParentToolName));
    }

    default String templateIdOutputPath() {
        return "$.templates[0].templateId";
    }

    default String templateIdInputPath() {
        return "$.templateId";
    }

    default boolean acceptsTemplateIdBinding(String outputPath, String inputPath) {
        String output = normalizePath(outputPath);
        String input = normalizePath(inputPath);
        boolean canonical = normalizePath(templateIdOutputPath()).equals(output)
            && normalizePath(templateIdInputPath()).equals(input);
        boolean compatibleLegacyShape = output.contains("templateid")
            && ("template".equals(input) || input.endsWith("templateid"));
        return canonical || compatibleLegacyShape;
    }

    /** Some assets can use a business-scoped template discovery tool without a separate asset lookup. */
    default boolean requiresAssetDiscovery() {
        return false;
    }

    /**
     * Compiles the input for a Runtime-injected template-discovery node from the
     * closest asset-discovery input. Asset plugins own this shape because it is
     * part of their published protocol rather than a planner convention.
     */
    default Map<String, Object> templateDiscoveryInput(Map<String, Object> assetInput) {
        return Map.of();
    }

    /**
     * Required executor inputs compiled by Runtime after governed template discovery.
     *
     * <p>Names are normalized (case and punctuation are ignored) by
     * {@link #runtimeOwnsExecutionInput(String)}. This is deliberately asset-specific:
     * declaring a field here never makes it optional for an unrelated executor.</p>
     */
    default Set<String> runtimeOwnedExecutionInputs() {
        return Set.of("parameters", "params", "arguments");
    }

    default boolean runtimeOwnsExecutionInput(String inputName) {
        String requested = normalizeField(inputName);
        return runtimeOwnedExecutionInputs().stream()
            .map(TemplateWorkflowPlugin::normalizeField)
            .anyMatch(requested::equals);
    }

    default boolean isExecution(TemplateWorkflowTool tool) {
        return tool != null && tool.role() == ToolWorkflowRole.TEMPLATE_EXECUTION && supports(tool);
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
    }

    private static String normalizeField(String value) {
        return normalizePath(value);
    }

    private static boolean sameTool(String left, String right) {
        return !normalizePath(left).isEmpty() && normalizePath(left).equals(normalizePath(right));
    }
}
