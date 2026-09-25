package com.chatchat.common.runtime.capability;

import java.util.Locale;

/** Stable, extensible business capability identity. */
public record CapabilityId(String namespace, String name, String version) implements Comparable<CapabilityId> {

    public CapabilityId {
        namespace = normalize(namespace, "general");
        name = normalize(name, null);
        version = normalize(version, "v1");
        if (name == null) throw new IllegalArgumentException("capability name is required");
    }

    public static CapabilityId parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("capability id is required");
        String[] parts = value.trim().split("\\.");
        if (parts.length < 2) return new CapabilityId("general", parts[0], "v1");
        String version = parts.length > 2 && parts[parts.length - 1].matches("v\\d+")
            ? parts[parts.length - 1] : "v1";
        int end = version.equals("v1") && !parts[parts.length - 1].matches("v\\d+")
            ? parts.length : parts.length - 1;
        StringBuilder name = new StringBuilder();
        for (int index = 1; index < end; index++) {
            if (!name.isEmpty()) name.append('.');
            name.append(parts[index]);
        }
        return new CapabilityId(parts[0], name.toString(), version);
    }

    public String value() { return namespace + "." + name + "." + version; }

    @Override public String toString() { return value(); }
    @Override public int compareTo(CapabilityId other) { return value().compareTo(other.value()); }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!normalized.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new IllegalArgumentException("invalid capability segment: " + value);
        }
        return normalized;
    }
}
