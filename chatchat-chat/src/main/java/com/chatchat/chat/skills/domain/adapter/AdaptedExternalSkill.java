package com.chatchat.chat.skills.domain.adapter;

import java.util.Map;

/** Format-neutral external skill material. It is not executable or publishable by itself. */
public record AdaptedExternalSkill(
    String name,
    String description,
    String instructions,
    String sourceFormat,
    Map<String, Object> metadata
) {
    public AdaptedExternalSkill {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
