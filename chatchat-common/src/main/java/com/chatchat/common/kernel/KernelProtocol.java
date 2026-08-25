package com.chatchat.common.kernel;

/** Versioned communication protocol exposed by a Kernel component. */
public record KernelProtocol(
    String id,
    String version,
    KernelChannel channel,
    String mediaType
) {
    public KernelProtocol {
        id = requireText(id, "protocol id");
        version = requireText(version, "protocol version");
        channel = channel == null ? KernelChannel.IN_PROCESS : channel;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/json" : mediaType.trim();
    }

    public boolean isCompatibleWith(KernelProtocol other) {
        return other != null
            && id.equals(other.id)
            && major(version).equals(major(other.version))
            && channel == other.channel;
    }

    private static String major(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        int dot = normalized.indexOf('.');
        return dot < 0 ? normalized : normalized.substring(0, dot);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Kernel " + field + " is required");
        }
        return value.trim();
    }
}
