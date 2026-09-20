package com.chatchat.chat.skills.domain;

import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.chatchat.chat.skills.domain.adapter.ExternalSkillAdapterGateway;
import com.chatchat.chat.skills.domain.adapter.ExternalSkillCompilation;
import com.chatchat.chat.skills.domain.adapter.ExternalSkillSource;
import com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class DomainSkillService implements DomainSkillRuntimePort {
    static final int DEFAULT_PUBLICATION_LIMIT = 5;
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    static final int MAX_MARKDOWN_CHARS = 512 * 1024;
    private static final String PUBLISHED = "PUBLISHED";
    private final DomainSkillRepository repository;
    private final DomainSkillCategoryRepository categoryRepository;
    private final McpLicenseEntitlementPort entitlementPort;
    private final DomainSkillIndexService indexService;
    private final DomainSkillRemoteImporter remoteImporter;
    private final ExternalSkillAdapterGateway externalSkillGateway;
    private final DomainSkillArtifactStore artifactStore;

    public Workspace workspace(String tenantId, String keyword, String category, String status, int page, int pageSize) {
        int p = Math.max(0, page), size = Math.max(1, Math.min(100, pageSize));
        Page<DomainSkillEntity> result = repository.search(tenantId, text(category), text(status), text(keyword), PageRequest.of(p, size));
        return new Workspace(result.getContent(), result.getTotalElements(), repository.countVisible(tenantId), p, size, result.getTotalPages(),
            categories(tenantId), quota(tenantId));
    }

    @Transactional
    public CategoryOption createCategory(String tenantId, String name) {
        String value = required(name, "Category name is required");
        if (value.length() > 120) throw new IllegalArgumentException("Category name must not exceed 120 characters");
        DomainSkillCategoryEntity category = categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, value)
            .orElseGet(() -> {
                DomainSkillCategoryEntity created = new DomainSkillCategoryEntity();
                created.setTenantId(tenantId);
                created.setName(value);
                return categoryRepository.save(created);
            });
        return categoryOption(tenantId, category, true);
    }

    @Transactional
    public CategoryOption renameCategory(String tenantId, String categoryId, String name) {
        DomainSkillCategoryEntity category = ownedCategory(categoryId, tenantId);
        String value = required(name, "Category name is required");
        if (value.length() > 120) throw new IllegalArgumentException("Category name must not exceed 120 characters");
        categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, value)
            .filter(existing -> !existing.getId().equals(category.getId()))
            .ifPresent(existing -> { throw new IllegalArgumentException("Category name already exists"); });

        String previousName = category.getName();
        List<DomainSkillEntity> skills = repository.findByTenantIdAndCategoryIgnoreCase(tenantId, previousName);
        category.setName(value);
        categoryRepository.save(category);
        for (DomainSkillEntity skill : skills) skill.setCategory(value);
        repository.saveAllAndFlush(skills);
        for (DomainSkillEntity skill : skills) {
            if (!PUBLISHED.equalsIgnoreCase(skill.getStatus()) || skill.isPublicationDirty()) continue;
            DomainSkillIndexService.IndexResult result = indexService.index(skill);
            if (!result.success()) {
                throw new IllegalStateException("Domain skill category index update failed: " + result.message());
            }
        }
        return categoryOption(tenantId, category, true);
    }

    @Transactional
    public void deleteCategory(String tenantId, String categoryId) {
        DomainSkillCategoryEntity category = ownedCategory(categoryId, tenantId);
        long skillCount = repository.countByTenantIdAndCategoryIgnoreCase(tenantId, category.getName());
        if (skillCount > 0) {
            throw new IllegalStateException("Category contains " + skillCount + " skill(s); move or delete them first");
        }
        categoryRepository.delete(category);
    }

    public List<DomainSkillEntity> publishedOptions(String tenantId) {
        return repository.findVisibleByStatus(tenantId, PUBLISHED);
    }

    @Override
    public List<DomainSkillContent> resolvePublished(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> ordered = ids.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(20).toList();
        Map<String, DomainSkillEntity> found = new HashMap<>();
        repository.findVisibleByIdInAndStatus(tenantId, ordered, PUBLISHED).forEach(s -> found.put(s.getId(), s));
        return ordered.stream().map(found::get).filter(Objects::nonNull)
            .map(s -> new DomainSkillContent(s.getId(), s.getName(), s.getCategory(), trim(s.getMarkdownContent(), 64 * 1024))).toList();
    }

    @Transactional
    public DomainSkillEntity save(String tenantId, String ownerId, SaveSkillCommand request) {
        if (request == null) throw new IllegalArgumentException("Skill content is required");
        String name = required(request.name(), "Skill name is required");
        String category = required(request.category(), "Skill category is required");
        String markdown = required(request.markdownContent(), "Markdown content is required");
        validateMarkdown(markdown);
        DomainSkillEntity skill = text(request.id()).isBlank() ? new DomainSkillEntity() : owned(request.id(), tenantId);
        boolean published = PUBLISHED.equalsIgnoreCase(skill.getStatus());
        if (skill.getId() == null) {
            skill.setTenantId(tenantId); skill.setOwnerId(ownerId); skill.setStatus("DRAFT"); skill.setSourceType("EDITOR");
        }
        skill.setName(name); skill.setCategory(category); skill.setDescription(text(request.description()));
        skill.setMarkdownContent(markdown.trim());
        if (published) skill.setPublicationDirty(true);
        ensureCategory(tenantId, category);
        return repository.save(skill);
    }

    @Transactional
    public DomainSkillEntity importFile(String tenantId, String ownerId, byte[] content, String originalFileName,
                                        String name, String category) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("Select a ZIP or Markdown file");
        if (content.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Skill file must not exceed 5MB");
        String fileName = safeFileName(originalFileName);
        return importBytes(tenantId, ownerId, content, fileName, name, category,
            "Imported from " + fileName, false);
    }

    @Transactional
    public DomainSkillEntity importUrl(String tenantId, String ownerId, String sourceUrl,
                                       String name, String category) {
        DomainSkillRemoteImporter.RemoteFile file = remoteImporter.download(sourceUrl);
        return importBytes(tenantId, ownerId, file.bytes(), file.fileName(), name, category,
            "Imported from " + trim(file.sourceUrl(), 1900), true);
    }

    @Transactional
    public DomainSkillEntity importUrl(String tenantId, String ownerId, String sourceUrl,
                                       String name, String category,
                                       DomainSkillRemoteImporter.DownloadRequest request) {
        DomainSkillRemoteImporter.RemoteFile file = remoteImporter.download(sourceUrl, request);
        return importBytes(tenantId, ownerId, file.bytes(), file.fileName(), name, category,
            "Imported from " + trim(file.sourceUrl(), 1900), true);
    }

    @Transactional
    public synchronized DomainSkillEntity publish(String tenantId, String id) {
        DomainSkillEntity skill = owned(id, tenantId);
        PublicationQuota quota = quota(tenantId);
        if (!quota.licenseValid()) throw new IllegalArgumentException("SKILL_LICENSE_INVALID: " + quota.message());
        if (!PUBLISHED.equalsIgnoreCase(skill.getStatus()) && quota.limited() && quota.published() >= quota.maximum())
            throw new IllegalArgumentException("SKILL_LICENSE_LIMIT_EXCEEDED: publication limit " + quota.maximum() + " reached");
        skill.setStatus(PUBLISHED); skill.setPublicationDirty(false); skill.setPublishedAt(Instant.now());
        repository.saveAndFlush(skill);
        DomainSkillIndexService.IndexResult result = indexService.index(skill);
        if (!result.success()) throw new IllegalStateException("Domain skill index write failed: " + result.message());
        return skill;
    }

    @Transactional
    public DomainSkillEntity recall(String tenantId, String id) {
        DomainSkillEntity skill = owned(id, tenantId);
        DomainSkillIndexService.IndexResult result = indexService.remove(id);
        if (!result.success()) throw new IllegalStateException("Domain skill index removal failed: " + result.message());
        skill.setStatus("RECALLED"); skill.setPublicationDirty(false);
        return repository.save(skill);
    }

    public ReindexResult reindex(String tenantId, String id) {
        DomainSkillEntity skill = repository.findVisibleById(tenantId, id)
            .orElseThrow(() -> new IllegalArgumentException("Domain skill does not exist or is outside the current tenant"));
        validateReindexable(skill);
        DomainSkillIndexService.IndexResult result = indexService.index(skill);
        if (!result.success()) throw new IllegalStateException("Domain skill index rebuild failed: " + result.message());
        return new ReindexResult(skill.getId(), skill.getName(), result.mode(), result.message());
    }

    public CategoryReindexResult reindexCategory(String tenantId, String category) {
        String value = required(category, "Skill category is required");
        List<DomainSkillEntity> skills = repository.findVisibleByCategoryAndStatus(tenantId, value, PUBLISHED);
        int reindexed = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (DomainSkillEntity skill : skills) {
            if (skill.isPublicationDirty()) {
                skipped++;
                continue;
            }
            DomainSkillIndexService.IndexResult result = indexService.index(skill);
            if (result.success()) reindexed++;
            else failures.add(skill.getId() + ": " + text(result.message()));
        }
        return new CategoryReindexResult(value, skills.size(), reindexed, skipped, failures.size(), failures);
    }

    @Transactional
    public void delete(String tenantId, String id) {
        DomainSkillEntity skill = owned(id, tenantId);
        if (skill.isBuiltin()) throw new IllegalArgumentException("Maintained domain skill cannot be deleted");
        DomainSkillIndexService.IndexResult result = indexService.remove(id);
        if (!result.success()) throw new IllegalStateException("Domain skill index removal failed: " + result.message());
        artifactStore.delete(tenantId, id);
        repository.delete(skill);
    }

    PublicationQuota quota(String tenantId) {
        McpLicenseEntitlementPort.SkillPublicationLimit e;
        try { e = entitlementPort.skillPublicationLimit(); } catch (RuntimeException ignored) { e = null; }
        int maximum = e == null || e.maxPublishedSkills() == null || e.maxPublishedSkills() <= 0 ? DEFAULT_PUBLICATION_LIMIT : e.maxPublishedSkills();
        boolean valid = e == null || e.licenseValid(), limited = e == null || e.limited();
        long published = repository.countVisibleByStatus(tenantId, PUBLISHED);
        return new PublicationQuota(valid, limited, maximum, published, limited ? Math.max(0, maximum - published) : -1,
            e == null || text(e.source()).isBlank() ? "DEFAULT" : e.source(),
            e == null ? "MCP did not return a skill publication limit; defaulting to 5" : text(e.message()));
    }

    private DomainSkillEntity owned(String id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Domain skill does not exist or is outside the current tenant"));
    }

    private DomainSkillCategoryEntity ownedCategory(String id, String tenantId) {
        return categoryRepository.findByIdAndTenantId(required(id, "Category id is required"), tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Skill category does not exist or is outside the current tenant"));
    }

    private void validateReindexable(DomainSkillEntity skill) {
        if (!PUBLISHED.equalsIgnoreCase(skill.getStatus())) {
            throw new IllegalArgumentException("Only published domain skills can rebuild the search index");
        }
        if (skill.isPublicationDirty()) {
            throw new IllegalArgumentException("Domain skill has unpublished changes; publish it again before rebuilding the index");
        }
    }

    private List<CategoryOption> categories(String tenantId) {
        Map<String, DomainSkillCategoryEntity> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        categoryRepository.findByTenantIdOrderByNameAsc(tenantId)
            .forEach(category -> categories.put(category.getName(), category));
        if (!"default".equalsIgnoreCase(tenantId)) {
            categoryRepository.findByTenantIdOrderByNameAsc("default")
                .forEach(category -> categories.putIfAbsent(category.getName(), category));
        }
        repository.findCategories(tenantId).stream().filter(Objects::nonNull).filter(name -> !name.isBlank())
            .forEach(name -> categories.putIfAbsent(name, null));
        return categories.entrySet().stream()
            .map(entry -> {
                DomainSkillCategoryEntity category = entry.getValue();
                boolean manageable = category != null && tenantId.equalsIgnoreCase(category.getTenantId());
                return category == null
                    ? new CategoryOption(null, entry.getKey(), repository.countVisibleByCategory(tenantId, entry.getKey()), false)
                    : categoryOption(tenantId, category, manageable);
            })
            .toList();
    }

    private CategoryOption categoryOption(String tenantId, DomainSkillCategoryEntity category, boolean manageable) {
        return new CategoryOption(category.getId(), category.getName(),
            repository.countVisibleByCategory(tenantId, category.getName()), manageable);
    }

    private void ensureCategory(String tenantId, String name) {
        if (categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name).isPresent()) return;
        DomainSkillCategoryEntity category = new DomainSkillCategoryEntity();
        category.setTenantId(tenantId);
        category.setName(name);
        categoryRepository.save(category);
    }

    private DomainSkillEntity importBytes(String tenantId, String ownerId, byte[] bytes, String originalFileName,
                                          String name, String category, String description, boolean remote) {
        String fileName = safeFileName(originalFileName), lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".zip"))
            throw new IllegalArgumentException("Only .zip, .md and .markdown files are supported");
        String markdown;
        try {
            markdown = lower.endsWith(".zip") ? markdownFromZip(bytes) : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read skill file", ex);
        }
        String sourceType = remote ? (lower.endsWith(".zip") ? "URL_ZIP" : "URL_MARKDOWN")
            : (lower.endsWith(".zip") ? "ZIP" : "MARKDOWN");
        String sourceReference = remote && description.startsWith("Imported from ")
            ? description.substring("Imported from ".length()) : fileName;
        ExternalSkillCompilation compilation = externalSkillGateway.adaptAndCompile(new ExternalSkillSource(
            fileName, sourceType, sourceReference, markdown, bytes));
        RuntimeSkillIr compiled = compilation.skillIr();
        markdown = compiled.markdownInstructions();
        validateMarkdown(markdown);
        DomainSkillEntity skill = new DomainSkillEntity();
        skill.setTenantId(tenantId); skill.setOwnerId(ownerId);
        String importedName = text(name).isBlank() ? compiled.name() : name.trim();
        skill.setName(trim(importedName.isBlank() ? inferName(markdown, fileName) : importedName, 200));
        skill.setCategory(required(category, "Skill category is required"));
        skill.setDescription(trim(text(compiled.description()).isBlank() ? description : compiled.description(), 2000));
        skill.setMarkdownContent(markdown.trim());
        skill.setSourceType(sourceType);
        skill.setId(UUID.randomUUID().toString());
        skill.setOriginalFileName(fileName); skill.setStatus("DRAFT");
        ensureCategory(tenantId, skill.getCategory());
        DomainSkillEntity saved = repository.save(skill);
        artifactStore.store(tenantId, saved.getId(), sourceType, fileName, compilation);
        return saved;
    }

    private String markdownFromZip(byte[] bytes) throws IOException {
        List<MarkdownEntry> files = new ArrayList<>(); int entries = 0, total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 200) throw new IllegalArgumentException("ZIP contains too many entries");
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
                while ((read = zip.read(buffer)) >= 0) { total += read; if (total > 2 * 1024 * 1024) throw new IllegalArgumentException("Markdown in ZIP exceeds 2MB"); out.write(buffer, 0, read); }
                files.add(new MarkdownEntry(name, out.toString(StandardCharsets.UTF_8)));
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No Markdown document found in ZIP");
        return files.stream().filter(f -> f.name().equalsIgnoreCase("SKILL.md")).findFirst()
            .or(() -> files.stream().filter(f -> f.name().toLowerCase(Locale.ROOT).endsWith("/skill.md")).min(Comparator.comparingInt(f -> f.name().length())))
            .map(MarkdownEntry::content).orElseGet(() -> files.stream().map(f -> "<!-- " + f.name() + " -->\n\n" + f.content()).reduce((a, b) -> a + "\n\n---\n\n" + b).orElseThrow());
    }

    private void validateMarkdown(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Markdown content is required"); if (value.length() > MAX_MARKDOWN_CHARS) throw new IllegalArgumentException("Markdown content exceeds 512K characters"); }
    private String inferName(String markdown, String fileName) { for (String line : markdown.split("\\R", 100)) { String v = line.trim(); if (v.startsWith("# ")) return trim(v.substring(2), 200); if (v.toLowerCase(Locale.ROOT).startsWith("name:")) return trim(v.substring(5), 200); } int dot = fileName.lastIndexOf('.'); return trim(dot > 0 ? fileName.substring(0, dot) : fileName, 200); }
    private String safeFileName(String value) { String name = text(value).replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1); return required(trim(name, 300), "File name is required"); }
    private String required(String value, String message) { String v = text(value); if (v.isBlank()) throw new IllegalArgumentException(message); return v; }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private String trim(String value, int max) { String v = text(value); return v.length() <= max ? v : v.substring(0, max); }

    private record MarkdownEntry(String name, String content) { }
    public record SaveSkillCommand(String id, String name, String category, String description,
                                   String markdownContent) { }
    public record Workspace(List<DomainSkillEntity> skills, long total, long skillCount, int page, int pageSize, int totalPages, List<CategoryOption> categories, PublicationQuota quota) { }
    public record CategoryOption(String id, String name, long count, boolean manageable) { }
    public record ReindexResult(String id, String name, String mode, String message) { }
    public record CategoryReindexResult(String category, int matched, int reindexed, int skipped, int failed,
                                        List<String> failures) { }
    public record PublicationQuota(boolean licenseValid, boolean limited, int maximum, long published, long remaining, String source, String message) { }
}
