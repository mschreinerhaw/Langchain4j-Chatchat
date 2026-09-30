package com.chatchat.chat.skills.runtime;

import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.resource.SkillResourceRequest;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.google.adk.skills.*;
import com.google.common.collect.*;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ADK progressive disclosure over the platform's authorized, versioned catalog. */
final class AdkAnalysisSkillSource implements SkillSource {
    private final Map<String, SkillDescriptor> catalog = new LinkedHashMap<>();
    private final Map<String, ResolvedSkill> loaded = new LinkedHashMap<>();
    private final SkillResolver resolver;
    private final SkillRoleContext identity;
    private int contentChars;
    AdkAnalysisSkillSource(List<SkillDescriptor> candidates, SkillResolver resolver, SkillRoleContext identity) {
        this.resolver = resolver; this.identity = identity;
        candidates.forEach(skill -> catalog.put(alias(skill.id()), skill));
    }
    static String alias(String id) {
        return "skill-" + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
    }
    synchronized boolean instructionsInspected() { return !loaded.isEmpty(); }
    private ResolvedSkill resolve(String name) throws SkillSourceException {
        var descriptor = catalog.get(name);
        if (descriptor == null) throw unavailable();
        var result = resolver.resolve(new SkillResolutionRequest(descriptor.id(), descriptor.version(), identity));
        if (!result.resolved() || !descriptor.version().equals(result.skill().descriptor().version())) throw unavailable();
        return result.skill();
    }
    private SkillSourceException unavailable() {
        return new SkillSourceException("Skill unavailable or changed", SkillSourceException.SKILL_NOT_FOUND);
    }
    @Override public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return Single.fromCallable(() -> {
            var result = ImmutableMap.<String, Frontmatter>builder();
            catalog.forEach((name, skill) -> result.put(name, frontmatter(name, skill)));
            return result.build();
        });
    }
    private Frontmatter frontmatter(String name, SkillDescriptor skill) {
        String description = skill.name() + ": " + skill.description();
        return Frontmatter.builder().name(name).description(description.substring(0, Math.min(1024, description.length()))).build();
    }
    @Override public Single<Frontmatter> loadFrontmatter(String name) {
        return Single.fromCallable(() -> {
            if (!catalog.containsKey(name)) throw unavailable();
            return frontmatter(name, catalog.get(name));
        });
    }
    @Override public Single<String> loadInstructions(String name) {
        return Single.fromCallable(() -> {
            var skill = resolve(name);
            synchronized (this) {
                if (loaded.containsKey(name) && !loaded.get(name).instructions().equals(skill.instructions())) throw unavailable();
                if (!loaded.containsKey(name)) {
                    if (loaded.size() >= 6 || contentChars + skill.instructions().length() > 72000)
                        throw new SkillSourceException("Skill context budget exceeded", SkillSourceException.SKILL_NOT_FOUND);
                    contentChars += skill.instructions().length();
                }
                loaded.put(name, skill);
            }
            return skill.instructions();
        });
    }
    @Override public Single<ImmutableList<String>> listResources(String name, String directory) {
        return Single.fromCallable(() -> {
            var skill = resolve(name);
            String prefix = directory.isEmpty() || directory.endsWith("/") ? directory : directory + "/";
            return ImmutableList.copyOf(skill.resources().stream().map(item -> item.relativePath())
                .filter(path -> path.startsWith(prefix) && !path.startsWith("scripts/")).toList());
        });
    }
    @Override public Single<ByteSource> loadResource(String name, String path) {
        return Single.fromCallable(() -> {
            var skill = resolve(name);
            synchronized (this) {
                if (!loaded.containsKey(name) || path.startsWith("scripts/")) throw unavailable();
                var declared = skill.resources().stream().filter(item -> item.relativePath().equals(path)).findFirst().orElseThrow(this::unavailable);
                var resource = resolver.readResource(new SkillResourceRequest(skill.descriptor().id(), declared.resourceId(), identity))
                    .orElseThrow(this::unavailable);
                if (!declared.digest().equals(resource.descriptor().digest()) || resource.content().length > 24000
                    || contentChars + resource.content().length > 96000) throw unavailable();
                contentChars += resource.content().length;
                return ByteSource.wrap(resource.content());
            }
        });
    }
    synchronized List<Map<String, Object>> applied(List<String> aliases) throws SkillSourceException {
        var result = new ArrayList<Map<String, Object>>();
        for (String name : new LinkedHashSet<>(aliases)) {
            if (!loaded.containsKey(name)) throw unavailable();
            var current = resolve(name); // Recheck permission and version before publishing the snapshot.
            var selected = loaded.get(name);
            if (!current.instructions().equals(selected.instructions())) throw unavailable();
            var descriptor = selected.descriptor();
            result.add(Map.of("id", descriptor.id(), "name", descriptor.name(), "version", descriptor.version(),
                "contentSha256", com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(selected.instructions())));
        }
        return List.copyOf(result);
    }
}
