package com.chatchat.chat.skills.domain.adapter;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** Deterministically classifies external artifacts before semantic model compilation. */
@Component
public final class SkillFormatDetector {
    public String detect(ExternalSkillSource source) {
        String name = text(source == null ? null : source.fileName()).replace('\\', '/').toLowerCase(Locale.ROOT);
        String type = text(source == null ? null : source.sourceType()).toUpperCase(Locale.ROOT);
        String content = text(source == null ? null : source.content());
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1).trim();
        if (name.endsWith("skill.md") && content.startsWith("---")
            && containsMetadataKey(content, "name") && containsMetadataKey(content, "description")) {
            return "ANTHROPIC_SKILL_MD";
        }
        if (type.contains("ZIP") || name.endsWith(".zip")) return "SKILL_MD_BUNDLE";
        return "GENERIC_MARKDOWN_SKILL";
    }

    private boolean containsMetadataKey(String content, String key) {
        int closing = content.indexOf("\n---", 3);
        String frontMatter = closing < 0 ? content : content.substring(0, closing);
        return frontMatter.lines().map(String::trim)
            .anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith(key + ":"));
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
}
