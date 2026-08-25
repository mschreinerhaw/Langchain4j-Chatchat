package com.chatchat.common.kernel;

import java.util.Set;

/** Stable identity and advertised capabilities of a Kernel implementation. */
public record KernelComponentDescriptor(
    String componentId,
    String componentType,
    String implementationVersion,
    Set<String> capabilities
) {
    public KernelComponentDescriptor {
        componentId = requireText(componentId, "component id");
        componentType = requireText(componentType, "component type");
        implementationVersion = implementationVersion == null || implementationVersion.isBlank()
            ? "1" : implementationVersion.trim();
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Kernel " + field + " is required");
        }
        return value.trim();
    }
}
