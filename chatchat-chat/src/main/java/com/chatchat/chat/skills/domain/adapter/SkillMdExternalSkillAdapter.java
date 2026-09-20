package com.chatchat.chat.skills.domain.adapter;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Reads the useful declarative subset of common SKILL.md documents. */
@Component
public final class SkillMdExternalSkillAdapter implements ExternalSkillAdapter {
    private static final int MAX_FRONT_MATTER_CHARS = 64 * 1024;
    private final SkillFormatDetector formatDetector;

    public SkillMdExternalSkillAdapter(SkillFormatDetector formatDetector) {
        this.formatDetector = formatDetector;
    }

    @Override
    public boolean supports(ExternalSkillSource source) {
        if (source == null) return false;
        String type = text(source.sourceType()).toUpperCase(Locale.ROOT);
        String fileName = text(source.fileName()).toLowerCase(Locale.ROOT);
        return type.contains("MARKDOWN") || type.contains("ZIP") || fileName.endsWith(".md")
            || fileName.endsWith(".markdown") || fileName.endsWith(".zip");
    }

    @Override
    public AdaptedExternalSkill adapt(ExternalSkillSource source) {
        if (!supports(source)) throw new IllegalArgumentException("Unsupported external skill format");
        ParsedMarkdown parsed = parse(source.content());
        String name = parsed.name().isBlank() ? inferName(parsed.body(), source.fileName()) : parsed.name();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("frontMatter", parsed.frontMatter());
        metadata.put("sourceType", text(source.sourceType()));
        metadata.put("sourceReference", text(source.sourceReference()));
        return new AdaptedExternalSkill(name, parsed.description(), parsed.body(), formatDetector.detect(source), metadata);
    }

    private ParsedMarkdown parse(String source) {
        String markdown = stripBom(source == null ? "" : source);
        FrontMatterSection section = frontMatter(markdown);
        if (section == null) return new ParsedMarkdown("", "", markdown.trim(), false);

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(MAX_FRONT_MATTER_CHARS);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(section.yaml());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid SKILL.md front matter", ex);
        }
        if (loaded != null && !(loaded instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("SKILL.md front matter must be a YAML object");
        }
        Map<?, ?> metadata = loaded instanceof Map<?, ?> map ? map : Map.of();
        return new ParsedMarkdown(supportedScalar(metadata, "name"), supportedScalar(metadata, "description"),
            section.body().trim(), true);
    }

    private FrontMatterSection frontMatter(String markdown) {
        int firstEnd = lineEnd(markdown, 0);
        if (firstEnd < 0 || !"---".equals(markdown.substring(0, firstEnd).trim())) return null;
        int yamlStart = skipLineBreak(markdown, firstEnd);
        int cursor = yamlStart;
        while (cursor <= markdown.length()) {
            int end = lineEnd(markdown, cursor);
            if (end < 0) end = markdown.length();
            if ("---".equals(markdown.substring(cursor, end).trim())) {
                if (end - yamlStart > MAX_FRONT_MATTER_CHARS) {
                    throw new IllegalArgumentException("SKILL.md front matter exceeds 64K characters");
                }
                int bodyStart = skipLineBreak(markdown, end);
                return new FrontMatterSection(markdown.substring(yamlStart, cursor), markdown.substring(bodyStart));
            }
            if (end == markdown.length()) break;
            cursor = skipLineBreak(markdown, end);
            if (cursor - yamlStart > MAX_FRONT_MATTER_CHARS) {
                throw new IllegalArgumentException("SKILL.md front matter exceeds 64K characters");
            }
        }
        throw new IllegalArgumentException("SKILL.md front matter is missing its closing delimiter");
    }

    private String supportedScalar(Map<?, ?> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) return "";
        if (!(value instanceof CharSequence)) {
            throw new IllegalArgumentException("SKILL.md " + key + " must be text");
        }
        return value.toString().trim();
    }

    private String inferName(String markdown, String fileName) {
        for (String line : text(markdown).split("\\R", 100)) {
            String value = line.trim();
            if (value.startsWith("# ")) return value.substring(2).trim();
        }
        String value = text(fileName).replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private int lineEnd(String value, int start) {
        if (start >= value.length()) return start;
        int lf = value.indexOf('\n', start);
        int cr = value.indexOf('\r', start);
        if (lf < 0) return cr;
        if (cr < 0) return lf;
        return Math.min(lf, cr);
    }

    private int skipLineBreak(String value, int index) {
        int cursor = index;
        if (cursor < value.length() && value.charAt(cursor) == '\r') cursor++;
        if (cursor < value.length() && value.charAt(cursor) == '\n') cursor++;
        return cursor;
    }

    private String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record ParsedMarkdown(String name, String description, String body, boolean frontMatter) {
    }

    private record FrontMatterSection(String yaml, String body) {
    }
}
