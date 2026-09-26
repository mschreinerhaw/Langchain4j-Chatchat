package com.chatchat.common.skills.federation;

import java.util.List;
import java.util.Map;

/** Stable Runtime OS boundary for discovering skill packages from any source. */
public interface SkillSourceRepository {

    String sourceType();

    SkillPage discover(SourceConnection source, String cursor, int pageSize);

    SkillManifest describe(SourceConnection source, String skillUri);

    SkillResource read(SourceConnection source, String resourceUri);

    record SourceConnection(String sourceId, String endpoint, String authorization) { }

    record SkillPage(List<SkillManifest> skills, String nextCursor) {
        public SkillPage {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    record SkillManifest(String uri, Map<String, Object> frontmatter,
                         List<ResourceDescriptor> resources, boolean dynamic,
                         Long ttlMs, String cacheScope) {
        public SkillManifest {
            frontmatter = frontmatter == null ? Map.of() : Map.copyOf(frontmatter);
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        public String name() { return String.valueOf(frontmatter.getOrDefault("name", "")).trim(); }
        public String description() { return String.valueOf(frontmatter.getOrDefault("description", "")).trim(); }
    }

    record ResourceDescriptor(String uri, String digest, long size) { }
    record SkillResource(String uri, String mimeType, byte[] bytes) {
        public SkillResource { bytes = bytes == null ? new byte[0] : bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
