package com.chatchat.chat.skills.federation;

import com.chatchat.chat.skills.domain.DomainSkillCategoryEntity;
import com.chatchat.chat.skills.domain.DomainSkillCategoryRepository;
import com.chatchat.chat.skills.domain.DomainSkillEntity;
import com.chatchat.chat.skills.domain.DomainSkillIndexService;
import com.chatchat.chat.skills.domain.DomainSkillRepository;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.security.InternalSecretCipher;
import com.chatchat.common.skills.federation.SkillSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Imports verifiable MCP Skills as governed local snapshots. */
@Service
@RequiredArgsConstructor
public class McpSkillFederationService {
    private final McpSkillSourceJpaRepository sourceRepository;
    private final DomainSkillRepository skillRepository;
    private final DomainSkillCategoryRepository categoryRepository;
    private final DomainSkillIndexService indexService;
    private final McpSkillSourceRepository mcpRepository;
    private final McpLicenseEntitlementPort entitlementPort;
    private final McpSkillFederationProperties properties;
    private final ObjectMapper objectMapper;

    public List<SourceView> sources(String tenantId) {
        return sourceRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream().map(SourceView::from).toList();
    }

    @Transactional
    public SourceView save(String tenantId, String ownerId, SourceCommand command) {
        requireLicensed();
        if (command == null) throw new IllegalArgumentException("MCP Skill source is required");
        McpSkillSourceEntity source = command.id() == null || command.id().isBlank()
            ? new McpSkillSourceEntity()
            : requireSource(tenantId, command.id());
        URI endpoint = validateEndpoint(command.endpoint(), command.allowPrivateNetwork());
        source.setTenantId(tenantId);
        source.setOwnerId(required(ownerId, "Owner is required"));
        source.setName(trim(required(command.name(), "Source name is required"), 200));
        source.setEndpoint(endpoint.toString());
        source.setDefaultCategory(trim(required(command.defaultCategory(), "Default category is required"), 120));
        source.setAllowPrivateNetwork(command.allowPrivateNetwork());
        source.setEnabled(command.enabled());
        if (command.authorization() != null && !command.authorization().isBlank()) {
            source.setAuthorizationHeader(InternalSecretCipher.encrypt(command.authorization().trim(), credentialKey()));
        }
        ensureCategory(tenantId, source.getDefaultCategory());
        return SourceView.from(sourceRepository.save(source));
    }

    public SyncResult synchronize(String tenantId, String sourceId) {
        requireLicensed();
        McpSkillSourceEntity source = requireSource(tenantId, sourceId);
        if (!source.isEnabled()) throw new IllegalStateException("MCP Skill source is disabled");
        validateEndpoint(source.getEndpoint(), source.isAllowPrivateNetwork());
        SkillSourceRepository.SourceConnection connection = connection(source);
        try {
            mcpRepository.verifyCapabilities(connection);
            List<SkillSourceRepository.SkillManifest> discovered = discoverAll(connection);
            int created = 0, updated = 0, unchanged = 0, skipped = 0;
            Set<String> observedUris = new HashSet<>();
            for (SkillSourceRepository.SkillManifest listed : discovered) {
                SkillSourceRepository.SkillManifest manifest = mcpRepository.describe(connection, listed.uri());
                observedUris.add(manifest.uri());
                if (manifest.dynamic()) { skipped++; continue; }
                SkillSourceRepository.ResourceDescriptor entry = manifest.resources().stream()
                    .filter(resource -> manifest.uri().equals(resource.uri())).findFirst().orElse(null);
                if (entry == null) { skipped++; continue; }
                SkillSourceRepository.SkillResource resource = mcpRepository.read(connection, manifest.uri());
                verify(entry, resource);
                String markdown = new String(resource.bytes(), StandardCharsets.UTF_8);
                if (markdown.isBlank() || resource.bytes().length > properties.getMaxSkillBytes()) {
                    throw new IllegalArgumentException("MCP SKILL.md is empty or exceeds the configured size: " + manifest.uri());
                }
                verifyFrontmatter(manifest, markdown);
                String digest = manifestDigest(manifest);
                DomainSkillEntity skill = skillRepository
                    .findByTenantIdAndFederatedSourceIdAndFederatedSkillUri(tenantId, sourceId, manifest.uri())
                    .orElse(null);
                if (skill == null) {
                    skill = new DomainSkillEntity();
                    skill.setId(UUID.nameUUIDFromBytes((sourceId + "\n" + manifest.uri()).getBytes(StandardCharsets.UTF_8)).toString());
                    skill.setTenantId(tenantId);
                    skill.setOwnerId(source.getOwnerId());
                    skill.setCategory(source.getDefaultCategory());
                    skill.setStatus("DRAFT");
                    skill.setSourceType("MCP");
                    created++;
                } else if (digest.equals(skill.getFederatedDigest())) {
                    skill.setFederatedSyncedAt(Instant.now());
                    unchanged++;
                    skillRepository.save(skill);
                    continue;
                } else {
                    skill.setPublicationDirty(true);
                    updated++;
                }
                skill.setName(trim(manifest.name(), 200));
                skill.setDescription(trim(manifest.description(), 2000));
                skill.setMarkdownContent(markdown.trim());
                skill.setFederatedSourceId(sourceId);
                skill.setFederatedSourceName(source.getName());
                skill.setFederatedSkillUri(manifest.uri());
                skill.setFederatedDigest(digest);
                skill.setFederatedManifestJson(writeJson(manifest));
                skill.setFederatedSyncedAt(Instant.now());
                skillRepository.save(skill);
            }
            int recalled = recallMissing(source, observedUris);
            source.setStatus(skipped == 0 ? "SYNCED" : "PARTIAL");
            source.setLastError(skipped == 0 ? null : skipped + " unverifiable or incomplete Skill(s) skipped");
            source.setLastDiscoveredCount(discovered.size());
            source.setLastSyncedAt(Instant.now());
            sourceRepository.save(source);
            return new SyncResult(discovered.size(), created, updated, unchanged, skipped, recalled, source.getStatus());
        } catch (RuntimeException failure) {
            source.setStatus("FAILED");
            source.setLastError(trim(failure.getMessage(), 2000));
            sourceRepository.save(source);
            throw failure;
        }
    }

    @Transactional
    public void delete(String tenantId, String sourceId) {
        requireLicensed();
        McpSkillSourceEntity source = requireSource(tenantId, sourceId);
        List<DomainSkillEntity> linked = skillRepository.findByTenantIdAndFederatedSourceId(tenantId, sourceId);
        if (linked.stream().anyMatch(skill -> "PUBLISHED".equalsIgnoreCase(skill.getStatus()))) {
            throw new IllegalStateException("Recall published Skills from this source before deleting it");
        }
        sourceRepository.delete(source);
    }

    private List<SkillSourceRepository.SkillManifest> discoverAll(SkillSourceRepository.SourceConnection connection) {
        int pageSize = Math.max(1, Math.min(200, properties.getPageSize()));
        int maximum = Math.max(pageSize, properties.getMaxSkillsPerSource());
        List<SkillSourceRepository.SkillManifest> result = new ArrayList<>();
        Set<String> cursors = new LinkedHashSet<>();
        String cursor = null;
        do {
            if (cursor != null && !cursors.add(cursor)) throw new IllegalArgumentException("MCP skills/list repeated a cursor");
            SkillSourceRepository.SkillPage page = mcpRepository.discover(connection, cursor, pageSize);
            result.addAll(page.skills());
            if (result.size() > maximum) throw new IllegalArgumentException("MCP Skill source exceeds the configured catalog limit");
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(result);
    }

    private int recallMissing(McpSkillSourceEntity source, Set<String> observedUris) {
        int recalled = 0;
        for (DomainSkillEntity skill : skillRepository.findByTenantIdAndFederatedSourceId(source.getTenantId(), source.getId())) {
            if (observedUris.contains(skill.getFederatedSkillUri())) continue;
            if ("PUBLISHED".equalsIgnoreCase(skill.getStatus())) indexService.remove(skill.getId());
            skill.setStatus("RECALLED");
            skill.setPublicationDirty(true);
            skillRepository.save(skill);
            recalled++;
        }
        return recalled;
    }

    private void verify(SkillSourceRepository.ResourceDescriptor descriptor, SkillSourceRepository.SkillResource resource) {
        if (descriptor.size() >= 0 && descriptor.size() != resource.bytes().length) {
            throw new IllegalArgumentException("MCP Skill resource size mismatch: " + descriptor.uri());
        }
        String expected = descriptor.digest() == null ? "" : descriptor.digest().trim().toLowerCase();
        String actual = "sha256:" + sha256(resource.bytes());
        if (!expected.equals(actual)) throw new IllegalArgumentException("MCP Skill resource digest mismatch: " + descriptor.uri());
    }

    private void verifyFrontmatter(SkillSourceRepository.SkillManifest manifest, String markdown) {
        String normalized = markdown != null && markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        String[] lines = normalized.split("\\R", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw new IllegalArgumentException("MCP SKILL.md frontmatter is missing: " + manifest.uri());
        }
        StringBuilder yaml = new StringBuilder();
        boolean closed = false;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) { closed = true; break; }
            if (yaml.length() > 64 * 1024) {
                throw new IllegalArgumentException("MCP SKILL.md frontmatter exceeds 64K: " + manifest.uri());
            }
            yaml.append(lines[i]).append('\n');
        }
        if (!closed) throw new IllegalArgumentException("MCP SKILL.md frontmatter is incomplete: " + manifest.uri());
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(64 * 1024);
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(options)).load(yaml.toString());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("MCP SKILL.md frontmatter is invalid: " + manifest.uri(), ex);
        }
        if (!(parsed instanceof java.util.Map<?, ?> parsedMap)
            || !objectMapper.valueToTree(parsedMap).equals(objectMapper.valueToTree(manifest.frontmatter()))) {
            throw new IllegalArgumentException("MCP SKILL.md frontmatter does not match its manifest: " + manifest.uri());
        }
    }

    private String manifestDigest(SkillSourceRepository.SkillManifest manifest) {
        String canonical = manifest.resources().stream().sorted(Comparator.comparing(SkillSourceRepository.ResourceDescriptor::uri))
            .map(resource -> resource.uri() + "\n" + resource.digest() + "\n" + resource.size())
            .reduce(manifest.uri(), (left, right) -> left + "\n" + right);
        return "sha256:" + sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private SkillSourceRepository.SourceConnection connection(McpSkillSourceEntity source) {
        String authorization = source.getAuthorizationHeader() == null || source.getAuthorizationHeader().isBlank()
            ? ""
            : InternalSecretCipher.decryptIfNecessary(source.getAuthorizationHeader(), credentialKey());
        return new SkillSourceRepository.SourceConnection(source.getId(), source.getEndpoint(), authorization);
    }

    private void requireLicensed() {
        McpLicenseEntitlementPort.SkillFederationEntitlement entitlement = entitlementPort.skillFederationEntitlement();
        if (entitlement == null || !entitlement.allowed()) {
            throw new SecurityException(entitlement == null ? "MCP Skills federation is not licensed" : entitlement.message());
        }
    }

    private McpSkillSourceEntity requireSource(String tenantId, String id) {
        return sourceRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("MCP Skill source does not exist or is outside the current tenant"));
    }

    private URI validateEndpoint(String value, boolean allowPrivateNetwork) {
        try {
            URI uri = URI.create(required(value, "MCP endpoint is required"));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("MCP endpoint must be an absolute HTTP(S) URL without credentials or fragment");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) throw new IllegalArgumentException("MCP endpoint resolves to an unsafe address");
                if (!allowPrivateNetwork && address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("MCP endpoint resolves to a private address; enable private network access explicitly");
                }
            }
            return uri;
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("MCP endpoint is invalid", ex); }
    }

    private void ensureCategory(String tenantId, String name) {
        if (categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name).isPresent()) return;
        DomainSkillCategoryEntity category = new DomainSkillCategoryEntity();
        category.setTenantId(tenantId);
        category.setName(name);
        categoryRepository.save(category);
    }

    private String credentialKey() {
        return required(properties.getCredentialKey(), "chatchat.skills.federation.credential-key must be configured");
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Unable to serialize MCP Skill manifest", ex); }
    }

    private String required(String value, String message) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record SourceCommand(String id, String name, String endpoint, String authorization,
                                String defaultCategory, boolean allowPrivateNetwork, boolean enabled) { }
    public record SourceView(String id, String name, String endpoint, String defaultCategory,
                             boolean allowPrivateNetwork, boolean enabled, String status, String lastError,
                             int lastDiscoveredCount, Instant lastSyncedAt, Instant updatedAt) {
        static SourceView from(McpSkillSourceEntity source) {
            return new SourceView(source.getId(), source.getName(), source.getEndpoint(), source.getDefaultCategory(),
                source.isAllowPrivateNetwork(), source.isEnabled(), source.getStatus(), source.getLastError(),
                source.getLastDiscoveredCount(), source.getLastSyncedAt(), source.getUpdatedAt());
        }
    }
    public record SyncResult(int discovered, int created, int updated, int unchanged,
                             int skipped, int recalled, String status) { }
}
