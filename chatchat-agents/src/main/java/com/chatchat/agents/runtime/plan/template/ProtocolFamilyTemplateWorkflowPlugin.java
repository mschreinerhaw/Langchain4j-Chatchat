package com.chatchat.agents.runtime.plan.template;

import java.util.Locale;
import java.util.Set;

/** Declarative base for the common discovery -> execution template protocol. */
public abstract class ProtocolFamilyTemplateWorkflowPlugin implements TemplateWorkflowPlugin {

    private final String id;
    private final Set<String> protocolFamilies;
    private final Set<String> assetTypes;

    protected ProtocolFamilyTemplateWorkflowPlugin(String id,
                                                    Set<String> protocolFamilies,
                                                    Set<String> assetTypes) {
        this.id = required(id, "plugin id");
        this.protocolFamilies = normalized(protocolFamilies);
        this.assetTypes = normalized(assetTypes);
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public boolean supports(TemplateWorkflowTool tool) {
        if (tool == null) return false;
        String family = normalize(tool.protocolFamily());
        String assetType = normalize(tool.assetType());
        return (!family.isEmpty() && protocolFamilies.contains(family))
            || (!assetType.isEmpty() && assetTypes.contains(assetType));
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(ProtocolFamilyTemplateWorkflowPlugin::normalize)
            .filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
}
