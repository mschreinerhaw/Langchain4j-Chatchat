package com.chatchat.chat.skills.runtime;

import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.resource.SkillResourceRequest;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.google.adk.skills.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One authorized skill per invocation. No disk access, scripts, global catalog, or cross-tenant cache. */
final class AdkAuthorizedSkillSource implements SkillSource {
    private final RuntimeAgentExecutionRequest request;
    private final SkillResolver resolver;
    final String alias;
    private final java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
    boolean instructionsLoaded() { return loaded.get(); }
    AdkAuthorizedSkillSource(RuntimeAgentExecutionRequest request, SkillResolver resolver) {
        this.request = request; this.resolver = resolver;
        alias = "skill-" + UUID.nameUUIDFromBytes(request.skill().descriptor().id().getBytes(StandardCharsets.UTF_8));
    }
    private void authorize(String name) throws SkillSourceException {
        if (!alias.equals(name) || !resolver.resolve(new SkillResolutionRequest(request.skill().descriptor().id(),
            request.skill().descriptor().version(), request.roleContext())).resolved())
            throw new SkillSourceException("Skill unavailable or changed", SkillSourceException.SKILL_NOT_FOUND);
    }
    @Override public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return loadFrontmatter(alias).map(fm -> ImmutableMap.of(alias, fm));
    }
    @Override public Single<Frontmatter> loadFrontmatter(String name) {
        return Single.fromCallable(() -> {
            authorize(name);
            String description = request.skill().descriptor().name() + ": " + request.skill().descriptor().description();
            return Frontmatter.builder().name(alias).description(description.substring(0, Math.min(1024, description.length()))).build();
        });
    }
    @Override public Single<String> loadInstructions(String name) {
        return Single.fromCallable(() -> { authorize(name); loaded.set(true); return request.skill().instructions(); });
    }
    @Override public Single<ImmutableList<String>> listResources(String name, String directory) {
        return Single.fromCallable(() -> {
            authorize(name);
            String prefix = directory.isEmpty() || directory.endsWith("/") ? directory : directory + "/";
            return ImmutableList.copyOf(request.skill().resources().stream().map(item -> item.relativePath())
                .filter(path -> path.startsWith(prefix)).toList());
        });
    }
    @Override public Single<ByteSource> loadResource(String name, String path) {
        return Single.fromCallable(() -> {
            authorize(name);
            var declared = request.skill().resources().stream().filter(item -> item.relativePath().equals(path))
                .findFirst().orElseThrow(() -> new SkillSourceException("Resource unavailable", SkillSourceException.RESOURCE_NOT_FOUND));
            var resource = resolver.readResource(new SkillResourceRequest(request.skill().descriptor().id(),
                declared.resourceId(), request.roleContext())).orElseThrow(() ->
                new SkillSourceException("Resource unavailable", SkillSourceException.RESOURCE_NOT_FOUND));
            if (resource.content().length > 256 * 1024 || !declared.digest().equals(resource.descriptor().digest()))
                throw new SkillSourceException("Resource changed or exceeds context budget", SkillSourceException.RESOURCE_NOT_FOUND);
            return ByteSource.wrap(resource.content());
        });
    }
}
