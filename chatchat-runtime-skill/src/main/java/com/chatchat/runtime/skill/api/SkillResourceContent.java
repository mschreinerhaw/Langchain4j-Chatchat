package com.chatchat.runtime.skill.api;

public record SkillResourceContent(SkillResourceDescriptor descriptor, byte[] content) {
    public SkillResourceContent {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        content = content == null ? new byte[0] : content.clone();
    }

    @Override public byte[] content() { return content.clone(); }
}
