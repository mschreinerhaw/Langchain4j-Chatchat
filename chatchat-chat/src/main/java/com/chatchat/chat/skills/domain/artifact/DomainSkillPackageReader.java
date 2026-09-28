package com.chatchat.chat.skills.domain.artifact;

import com.chatchat.runtime.skill.api.skill.SkillRequirements;
import com.chatchat.runtime.skill.api.resource.SkillResourceContent;
import com.chatchat.runtime.skill.api.resource.SkillResourceDescriptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads immutable SKILL.md bundle resources and non-authoritative resource declarations. */
@Component
public class DomainSkillPackageReader {
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private final DomainSkillSourceArtifactRepository artifacts;
    private final ObjectMapper objectMapper;
    private final DomainSkillCompilationRepository compilations;

    @org.springframework.beans.factory.annotation.Autowired
    public DomainSkillPackageReader(DomainSkillSourceArtifactRepository artifacts, ObjectMapper objectMapper,
                                    DomainSkillCompilationRepository compilations) {
        this.artifacts = artifacts; this.objectMapper = objectMapper; this.compilations = compilations;
    }
    public DomainSkillPackageReader(DomainSkillSourceArtifactRepository artifacts, ObjectMapper objectMapper) {
        this(artifacts, objectMapper, null);
    }

    public record CompilationView(String id, com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr protocol) { }

    @Transactional(readOnly = true)
    public Optional<CompilationView> latestCompilation(String tenantId, String skillId) {
        if (compilations == null) return Optional.empty();
        return compilations.findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(skillId, tenantId).map(this::compiled);
    }

    private CompilationView compiled(DomainSkillCompilationEntity entity) {
        try {
            var ir = objectMapper.readValue(entity.getSkillIrJson(), com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr.class);
            if (!java.util.Set.of("runtime_skill_ir.v1", "runtime_skill_ir.v2").contains(ir.schemaVersion()))
                throw new IllegalArgumentException("Unsupported Skill IR schema");
            return new CompilationView(entity.getId(), ir);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid compiled Skill protocol", error); }
    }

    @Transactional(readOnly = true)
    public PackageView readPublished(String tenantId, String skillId, String compilationId) {
        if (compilationId == null || compilationId.isBlank()) {
            var legacy = read(tenantId, skillId);
            var r = legacy.requirements();
            return new PackageView(new SkillRequirements(r.documentIds(), r.knowledgeBaseIds(), r.mcpToolIds(),
                r.agentIds(), r.workflowIds()), legacy.resources());
        }
        var entry = compilations.findById(compilationId)
            .filter(item -> tenantId.equals(item.getTenantId()) && skillId.equals(item.getSkillId()))
            .orElseThrow(() -> new IllegalArgumentException("Published compilation is unavailable"));
        var source = artifacts.findById(entry.getSourceId())
            .filter(item -> tenantId.equals(item.getTenantId()) && skillId.equals(item.getSkillId()))
            .orElseThrow(() -> new IllegalArgumentException("Published source is unavailable"));
        return new PackageView(compiled(entry).protocol().execution().requirements(), resources(source.getOriginalArtifact(), source.getSourceType()));
    }

    @Transactional(readOnly = true)
    public PackageView read(String tenantId, String skillId) {
        Optional<DomainSkillSourceArtifactEntity> stored = artifacts
            .findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(skillId, tenantId);
        if (stored.isEmpty()) return PackageView.empty();
        DomainSkillSourceArtifactEntity source = stored.get();
        return new PackageView(requirements(source.getParsedDocumentJson()),
            resources(source.getOriginalArtifact(), source.getSourceType()));
    }

    @Transactional(readOnly = true)
    public Optional<SkillResourceContent> readResource(String tenantId, String skillId, String resourceId) {
        String requested = safePath(resourceId);
        if (requested.isBlank()) return Optional.empty();
        Optional<DomainSkillSourceArtifactEntity> stored = artifacts
            .findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(skillId, tenantId);
        if (stored.isEmpty()) return Optional.empty();
        for (ResourceEntry entry : entries(stored.get().getOriginalArtifact(), stored.get().getSourceType(), true)) {
            if (entry.path().equals(requested)) {
                return Optional.of(new SkillResourceContent(descriptor(entry), entry.content()));
            }
        }
        return Optional.empty();
    }

    private SkillRequirements requirements(String parsedJson) {
        if (parsedJson == null || parsedJson.isBlank()) return SkillRequirements.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(parsedJson, new TypeReference<>() { });
            Map<String, Object> metadata = objectMap(parsed.get("metadata"));
            Map<String, Object> frontMatter = objectMap(metadata.get("frontMatter"));
            Map<String, Object> requirements = objectMap(first(frontMatter, "requirements", "permissions"));
            return new SkillRequirements(
                values(frontMatter, requirements, "allowedDocuments", "allowed_documents", "documents", "documentIds"),
                values(frontMatter, requirements, "allowedKnowledgeBases", "allowed_knowledge_bases", "knowledgeBases", "knowledgeBaseIds"),
                values(frontMatter, requirements, "allowedMcp", "allowed_mcp", "mcpTools", "mcp_tool_ids"),
                values(frontMatter, requirements, "allowedAgents", "allowed_agents", "agents", "agentIds"),
                values(frontMatter, requirements, "workflows", "workflowIds", "workflow_ids"),
                dataRequirements(frontMatter));
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to read Skill resource declarations", error);
        }
    }

    private List<com.chatchat.runtime.skill.api.skill.SkillDataRequirement> dataRequirements(Map<String, Object> frontMatter) {
        Object declared = frontMatter.get("requiredData");
        if (declared == null) return List.of();
        if (!(declared instanceof List<?>)) throw new IllegalArgumentException("requiredData must be a list");
        return objectMapper.convertValue(declared,
            new TypeReference<List<com.chatchat.runtime.skill.api.skill.SkillDataRequirement>>() { });
    }

    private List<SkillResourceDescriptor> resources(byte[] artifact, String sourceType) {
        return entries(artifact, sourceType, false).stream().map(this::descriptor).toList();
    }

    private List<ResourceEntry> entries(byte[] artifact, String sourceType, boolean includeContent) {
        if (artifact == null || artifact.length == 0 || sourceType == null
            || !sourceType.toUpperCase(Locale.ROOT).contains("ZIP")) return List.of();
        List<ResourceEntry> result = new ArrayList<>();
        int entries = 0;
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifact), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("Skill bundle contains too many entries");
                String path = safePath(entry.getName());
                if (entry.isDirectory() || path.isBlank() || skillMarkdown(path)) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int size = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    size += read;
                    total += read;
                    if (size > MAX_RESOURCE_BYTES || total > MAX_TOTAL_BYTES)
                        throw new IllegalArgumentException("Skill bundle resource limit exceeded");
                    output.write(buffer, 0, read);
                }
                byte[] content = output.toByteArray();
                result.add(new ResourceEntry(path, mediaType(path), sha256(content),
                    includeContent ? content : new byte[0]));
            }
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to read Skill bundle resources", error);
        }
    }

    private SkillResourceDescriptor descriptor(ResourceEntry entry) {
        String kind = entry.path().startsWith("references/") ? "REFERENCE"
            : entry.path().startsWith("scripts/") ? "SCRIPT"
            : entry.path().startsWith("assets/") ? "ASSET" : "RESOURCE";
        return new SkillResourceDescriptor(entry.path(), entry.path(), entry.mediaType(), entry.digest(),
            Map.of("kind", kind));
    }

    private List<String> values(Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
        Object value = first(primary, keys);
        if (value == null) value = first(secondary, keys);
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            iterable.forEach(item -> { if (item != null && !item.toString().isBlank()) result.add(item.toString().trim()); });
            return result.stream().distinct().toList();
        }
        if (value instanceof CharSequence text && !text.toString().isBlank()) {
            return java.util.Arrays.stream(text.toString().split("[,\\n]"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private Object first(Map<String, Object> source, String... keys) {
        if (source == null) return null;
        for (String key : keys) if (source.containsKey(key)) return source.get(key);
        return null;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> { if (key != null) result.put(key.toString(), item); });
        return result;
    }

    private String safePath(String value) {
        String path = value == null ? "" : value.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isBlank() || path.contains("../") || path.equals("..") || path.indexOf('\0') >= 0) return "";
        return path;
    }

    private boolean skillMarkdown(String path) {
        return path.equalsIgnoreCase("SKILL.md") || path.toLowerCase(Locale.ROOT).endsWith("/skill.md");
    }

    private String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".py")
            || lower.endsWith(".js") || lower.endsWith(".sql")) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    public record PackageView(SkillRequirements requirements, List<SkillResourceDescriptor> resources) {
        public PackageView {
            requirements = requirements == null ? SkillRequirements.empty() : requirements;
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
        public static PackageView empty() { return new PackageView(SkillRequirements.empty(), List.of()); }
    }

    private record ResourceEntry(String path, String mediaType, String digest, byte[] content) { }
}
