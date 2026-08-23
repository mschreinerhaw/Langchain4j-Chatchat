package com.chatchat.chat.uiartifact;

import com.chatchat.chat.presentation.UserFacingContentSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UiArtifactService {

    public static final String ARTIFACT_SCHEMA_VERSION = "enterprise_ui_artifact_v1";
    public static final String REFERENCE_SCHEMA_VERSION = "ui_artifact_ref_v1";
    public static final String CATALOG_VERSION = "enterprise_catalog_v1";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ArtifactBlobStore blobStore;
    private final UiArtifactRepository repository;
    private final UiArtifactProperties properties;
    private final ObjectMapper objectMapper;

    public UiArtifactService(ArtifactBlobStore blobStore,
                             UiArtifactRepository repository,
                             UiArtifactProperties properties,
                             ObjectMapper objectMapper) {
        this.blobStore = blobStore;
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Presentation externalizeIfNeeded(String tenantId,
                                            String taskId,
                                            Map<String, Object> uiResponse) {
        if (!properties.isEnabled() || uiResponse == null || uiResponse.isEmpty()
            || (!properties.isAlwaysExternalize()
                && serializedSize(uiResponse) < properties.getExternalizeThresholdBytes())) {
            return new Presentation(uiResponse == null ? Map.of() : uiResponse, null, false);
        }

        String artifactId = "ui_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, StoredResource> resources = new LinkedHashMap<>();
        Map<String, Object> resourceCatalog = new LinkedHashMap<>();
        Map<String, Object> elements = new LinkedHashMap<>();
        List<String> reportChildren = new ArrayList<>();

        String answer = UserFacingContentSanitizer.removeInternalEvidenceMarkers(
            text(uiResponse.get("answer")));
        String explicitHtml = UserFacingContentSanitizer.removeInternalEvidenceMarkers(
            explicitReportHtml(uiResponse));
        if (!answer.isBlank()) {
            // Keep authored Markdown as the canonical report resource. Converting it to an opaque
            // HTML blob here loses semantics and makes evidence/report styling much harder to control.
            addResource(resources, resourceCatalog, artifactId, "answer", answer, "text/markdown");
            elements.put("answer", element("Markdown", Map.of("resourceId", "answer"), List.of()));
            reportChildren.add("answer");
        } else if (!explicitHtml.isBlank()) {
            addResource(resources, resourceCatalog, artifactId, "report", explicitHtml, "text/html");
            elements.put("report-html", element("Html", Map.of("resourceId", "report"), List.of()));
            reportChildren.add("report-html");
        }

        String evidenceSummary = text(uiResponse.get("evidenceSummary"));
        if (!evidenceSummary.isBlank()) {
            addResource(resources, resourceCatalog, artifactId, "evidence-summary", evidenceSummary, "text/markdown");
            elements.put("evidence-summary", element("Notice", Map.of(
                "title", "证据摘要",
                "tone", "info",
                "resourceId", "evidence-summary"
            ), List.of()));
            reportChildren.add("evidence-summary");
        }

        Object visualizationSpec = uiResponse.get("visualizationSpec");

        Object citations = uiResponse.get("citations");
        if (citations instanceof List<?> list && !list.isEmpty()) {
            addResource(resources, resourceCatalog, artifactId, "citations", citations, "application/json");
            elements.put("citations", element("EvidenceList", Map.of(
                "title", "引用与证据",
                "resourceId", "citations"
            ), List.of()));
            // Keep the resource and manifest element for provenance/audit access, but do not
            // render a citation-and-evidence accordion in the answer report.
        }

        Object evidencePremises = uiResponse.get("evidencePremises");
        if (evidencePremises instanceof List<?> list && !list.isEmpty()) {
            addResource(resources, resourceCatalog, artifactId, "evidence-premises", evidencePremises, "application/json");
            elements.put("evidence-premises", element("EvidenceList", Map.of(
                "title", "证据前提",
                "resourceId", "evidence-premises"
            ), List.of()));
            // Keep premises as retained metadata without rendering them as answer content.
        }

        elements.put("report", element("Report", Map.of(
            "status", text(uiResponse.get("status")),
            "taskId", taskId == null ? "" : taskId
        ), reportChildren));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("root", "report");
        spec.put("elements", elements);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", ARTIFACT_SCHEMA_VERSION);
        manifest.put("artifactId", artifactId);
        manifest.put("revision", 1);
        manifest.put("catalogVersion", CATALOG_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("spec", spec);
        manifest.put("resources", resourceCatalog);

        UiArtifactEntity metadataEntity = metadataEntity(
            tenantId, taskId, artifactId, resources.size(), properties.getTtlSeconds());
        metadataEntity = repository.save(metadataEntity);
        try {
            long totalBytes = 0;
            for (Map.Entry<String, StoredResource> resource : resources.entrySet()) {
                StoredResource stored = resource.getValue();
                putObject(tenantId, artifactId, stored.objectKey(), stored.bytes(), stored.mediaType());
                totalBytes += stored.bytes().length;
            }
            byte[] manifestBytes = serialize(manifest);
            putObject(tenantId, artifactId, "manifest.json", manifestBytes, "application/json");
            totalBytes += manifestBytes.length;
            metadataEntity.setTotalBytes(totalBytes);
            metadataEntity.setStatus("ACTIVE");
            repository.save(metadataEntity);
        } catch (RuntimeException ex) {
            resources.keySet().forEach(resourceId -> blobStore.delete(
                new ArtifactLocation(tenantId, artifactId, resources.get(resourceId).objectKey())));
            blobStore.delete(new ArtifactLocation(tenantId, artifactId, "manifest.json"));
            metadataEntity.setStatus("FAILED");
            repository.save(metadataEntity);
            throw ex;
        }

        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("contractVersion", REFERENCE_SCHEMA_VERSION);
        reference.put("artifactId", artifactId);
        reference.put("revision", 1);
        reference.put("catalogVersion", CATALOG_VERSION);
        reference.put("manifestUrl", "/ui-artifacts/" + artifactId);
        String renderMode = !answer.isBlank() ? "markdown" : "html";
        reference.put("renderMode", renderMode);

        Map<String, Object> lightweight = new LinkedHashMap<>();
        lightweight.put("contractVersion", "ui_response_v2");
        lightweight.put("sourceContractVersion", text(uiResponse.get("contractVersion")));
        lightweight.put("status", text(uiResponse.get("status")));
        lightweight.put("answer", preview(answer));
        lightweight.put("citations", List.of());
        lightweight.put("evidencePremises", List.of());
        lightweight.put("confidence", uiResponse.get("confidence"));
        lightweight.put("evidenceSummary", "");
        lightweight.put("visualization", visualizationSpec == null
            ? Map.of("type", "artifact")
            : Map.of("type", "inline", "spec", visualizationSpec));
        if (visualizationSpec != null) {
            // Keep the established VisualizationRenderer path available. The artifact owns the
            // report body; the visualization remains inline to preserve chart/table switching,
            // export, resize, and drill-down behavior without rendering it twice.
            lightweight.put("visualizationSpec", visualizationSpec);
        }
        lightweight.put("uiArtifact", reference);
        lightweight.put("renderMode", renderMode);
        return new Presentation(lightweight, reference, true);
    }

    @Transactional
    public Optional<Map<String, Object>> manifest(String tenantId, String artifactId) {
        if (!isReadable(tenantId, artifactId)) {
            return Optional.empty();
        }
        return readObject(new ArtifactLocation(tenantId, artifactId, "manifest.json"), MAP_TYPE);
    }

    @Transactional
    public Optional<Object> resource(String tenantId, String artifactId, String resourceId) {
        if (!isReadable(tenantId, artifactId)) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> manifest = readObject(
            new ArtifactLocation(normalizedTenant(tenantId), artifactId, "manifest.json"), MAP_TYPE);
        if (manifest.isEmpty() || !(manifest.get().get("resources") instanceof Map<?, ?> catalog)
            || !(catalog.get(resourceId) instanceof Map<?, ?> descriptor)) {
            return Optional.empty();
        }
        String mediaType = text(descriptor.get("mediaType"));
        String objectKey = text(descriptor.get("objectKey"));
        ArtifactLocation location = new ArtifactLocation(
            normalizedTenant(tenantId), artifactId,
            objectKey.isBlank() ? resourceKey(resourceId) : objectKey);
        if ("text/html".equalsIgnoreCase(mediaType)) {
            return readText(location)
                .map(UserFacingContentSanitizer::removeInternalEvidenceMarkers)
                .map(value -> (Object) value);
        }
        Optional<Object> resource = readObject(location, Object.class);
        if ("text/markdown".equalsIgnoreCase(mediaType)) {
            return resource.map(value -> value instanceof String content
                ? UserFacingContentSanitizer.removeInternalEvidenceMarkers(content)
                : value);
        }
        return resource;
    }

    @Transactional
    public boolean delete(String tenantId, String artifactId) {
        String normalizedTenant = normalizedTenant(tenantId);
        Optional<UiArtifactEntity> found = repository.findByArtifactIdAndTenantId(artifactId, normalizedTenant);
        if (found.isEmpty() || "DELETED".equals(found.get().getStatus())) {
            return false;
        }
        deleteObjects(normalizedTenant, artifactId);
        UiArtifactEntity entity = found.get();
        entity.setStatus("DELETED");
        repository.save(entity);
        return true;
    }

    @Scheduled(fixedDelayString = "${chatchat.ui-artifact.cleanup-interval-ms:3600000}")
    @Transactional
    public void expireDueArtifacts() {
        Instant now = Instant.now();
        List<UiArtifactEntity> expired = repository
            .findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc("ACTIVE", now);
        for (UiArtifactEntity entity : expired) {
            deleteObjects(entity.getTenantId(), entity.getArtifactId());
            entity.setStatus("EXPIRED");
            repository.save(entity);
        }
    }

    private void deleteObjects(String tenantId, String artifactId) {
        readObject(new ArtifactLocation(tenantId, artifactId, "manifest.json"), MAP_TYPE).ifPresent(manifest -> {
            Object catalog = manifest.get("resources");
            if (catalog instanceof Map<?, ?> resources) {
                for (Object resourceId : resources.keySet()) {
                    Object descriptor = resources.get(resourceId);
                    String objectKey = descriptor instanceof Map<?, ?> map ? text(map.get("objectKey")) : "";
                    blobStore.delete(new ArtifactLocation(
                        tenantId, artifactId, objectKey.isBlank()
                            ? resourceKey(String.valueOf(resourceId)) : objectKey));
                }
            }
        });
        blobStore.delete(new ArtifactLocation(tenantId, artifactId, "manifest.json"));
    }

    private void addResource(Map<String, StoredResource> resources,
                             Map<String, Object> catalog,
                             String artifactId,
                             String resourceId,
                             Object value,
                             String mediaType) {
        byte[] bytes = "text/html".equalsIgnoreCase(mediaType)
            ? String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
            : serialize(value);
        String objectKey = resourceKey(resourceId, mediaType);
        resources.put(resourceId, new StoredResource(bytes, mediaType, objectKey));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("href", "/ui-artifacts/" + artifactId + "/resources/" + resourceId);
        metadata.put("mediaType", mediaType);
        metadata.put("objectKey", objectKey);
        metadata.put("byteLength", bytes.length);
        metadata.put("sha256", sha256(bytes));
        catalog.put(resourceId, metadata);
    }

    private void putObject(String tenantId,
                           String artifactId,
                           String objectKey,
                           byte[] bytes,
                           String mediaType) {
        ArtifactObjectMetadata metadata = new ArtifactObjectMetadata(
            mediaType, bytes.length, sha256(bytes), Map.of("schema-version", ARTIFACT_SCHEMA_VERSION));
        blobStore.put(
            new ArtifactLocation(tenantId, artifactId, objectKey),
            new ByteArrayInputStream(bytes),
            metadata
        );
    }

    private <T> Optional<T> readObject(ArtifactLocation location, Class<T> type) {
        return blobStore.get(location).map(content -> {
            try (content) {
                return objectMapper.readValue(content.stream(), type);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read artifact object " + location.objectKey(), ex);
            }
        });
    }

    private <T> Optional<T> readObject(ArtifactLocation location, TypeReference<T> type) {
        return blobStore.get(location).map(content -> {
            try (content) {
                return objectMapper.readValue(content.stream(), type);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read artifact object " + location.objectKey(), ex);
            }
        });
    }

    private Optional<String> readText(ArtifactLocation location) {
        return blobStore.get(location).map(content -> {
            try (content) {
                return new String(content.stream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read artifact object " + location.objectKey(), ex);
            }
        });
    }

    private boolean isReadable(String tenantId, String artifactId) {
        String normalizedTenant = normalizedTenant(tenantId);
        Optional<UiArtifactEntity> existing = repository
            .findByArtifactIdAndTenantId(artifactId, normalizedTenant);
        if (existing.isPresent()) {
            return existing.get().readableAt(Instant.now());
        }
        if (!properties.isMigrateLegacyOnRead()) {
            return false;
        }
        ArtifactLocation manifestLocation = new ArtifactLocation(normalizedTenant, artifactId, "manifest.json");
        Optional<Map<String, Object>> legacyManifest = readObject(manifestLocation, MAP_TYPE);
        if (legacyManifest.isEmpty()) {
            return false;
        }
        int resourceCount = legacyManifest.get().get("resources") instanceof Map<?, ?> resources
            ? resources.size() : 0;
        UiArtifactEntity migrated = metadataEntity(
            normalizedTenant, "", artifactId, resourceCount, properties.getTtlSeconds());
        migrated.setStatus("ACTIVE");
        blobStore.get(manifestLocation).ifPresent(content -> {
            try (content) {
                migrated.setTotalBytes(content.metadata().contentLength());
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to close legacy artifact manifest", ex);
            }
        });
        repository.save(migrated);
        return true;
    }

    private UiArtifactEntity metadataEntity(String tenantId,
                                            String taskId,
                                            String artifactId,
                                            int resourceCount,
                                            long ttlSeconds) {
        UiArtifactEntity entity = new UiArtifactEntity();
        entity.setArtifactId(artifactId);
        entity.setTenantId(normalizedTenant(tenantId));
        entity.setTaskId(taskId == null ? "" : taskId);
        entity.setSchemaVersion(ARTIFACT_SCHEMA_VERSION);
        entity.setCatalogVersion(CATALOG_VERSION);
        entity.setRevision(1);
        entity.setStatus("WRITING");
        entity.setStoreType(blobStore.storeType());
        entity.setManifestKey("manifest.json");
        entity.setTotalBytes(0);
        entity.setResourceCount(resourceCount);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (ttlSeconds > 0) {
            entity.setExpiresAt(now.plusSeconds(ttlSeconds));
        }
        return entity;
    }

    private static Map<String, Object> element(String type, Map<String, Object> props, List<String> children) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("type", type);
        element.put("props", props);
        element.put("children", children);
        return element;
    }

    private int serializedSize(Object value) {
        return serialize(value).length;
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Artifact content cannot be serialized", ex);
        }
    }

    private String explicitReportHtml(Map<String, Object> uiResponse) {
        return firstText(
            uiResponse.get("html"),
            uiResponse.get("reportHtml"),
            uiResponse.get("answerHtml"),
            uiResponse.get("htmlContent")
        );
    }

    private String preview(String answer) {
        int limit = Math.max(0, properties.getAnswerPreviewCharacters());
        if (answer.length() <= limit) {
            return answer;
        }
        return answer.substring(0, limit) + "\n\n完整内容已转存为动态报告。";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String resourceKey(String resourceId) {
        return new ArtifactLocation("default", "validation", "resources/" + resourceId + ".json").objectKey();
    }

    private static String resourceKey(String resourceId, String mediaType) {
        String extension = "text/html".equalsIgnoreCase(mediaType) ? ".html" : ".json";
        return new ArtifactLocation("default", "validation", "resources/" + resourceId + extension).objectKey();
    }

    private static String normalizedTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    public record Presentation(Map<String, Object> uiResponse,
                               Map<String, Object> reference,
                               boolean externalized) {
    }

    private record StoredResource(byte[] bytes, String mediaType, String objectKey) {
    }
}
