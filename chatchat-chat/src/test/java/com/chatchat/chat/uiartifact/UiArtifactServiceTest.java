package com.chatchat.chat.uiartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiArtifactServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sharedModeRequiresMountedVolumeMarkerAndReportsSharedStoreType() throws Exception {
        UiArtifactProperties properties = properties(100);
        properties.setStoreType("shared");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new FilesystemArtifactBlobStore(new ObjectMapper(), properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("marker is missing");

        Files.writeString(temporaryDirectory.resolve(".chatchat-artifact-store"), "chatchat\n");
        ArtifactBlobStore store = new FilesystemArtifactBlobStore(new ObjectMapper(), properties);
        assertThat(store.storeType()).isEqualTo("shared");
    }

    @Test
    void externalizesLargeUiResponseIntoManifestAndIndependentResources() {
        Fixture fixture = fixture(64);

        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a",
            "task-1",
            Map.of(
                "contractVersion", "ui_response_v1",
                "status", "SUCCESS",
                "answer", "完整报告内容".repeat(30),
                "citations", List.of(Map.of("title", "证据一", "text", "证据内容")),
                "evidencePremises", List.of(Map.of("rank", 1, "text", "证据前提")),
                "visualizationSpec", Map.of("type", "table", "rows", List.of(Map.of("value", 42)))
            )
        );

        assertThat(presentation.externalized()).isTrue();
        assertThat(presentation.uiResponse())
            .containsEntry("contractVersion", "ui_response_v2")
            .containsEntry("renderMode", "markdown")
            .containsKey("uiArtifact")
            .containsKey("visualizationSpec");

        String artifactId = String.valueOf(presentation.reference().get("artifactId"));
        Map<String, Object> manifest = fixture.service().manifest("tenant-a", artifactId).orElseThrow();
        assertThat(manifest)
            .containsEntry("schemaVersion", UiArtifactService.ARTIFACT_SCHEMA_VERSION)
            .containsEntry("catalogVersion", UiArtifactService.CATALOG_VERSION);
        assertThat(fixture.service().resource("tenant-a", artifactId, "report")).isEmpty();
        assertThat(fixture.service().resource("tenant-a", artifactId, "answer"))
            .hasValueSatisfying(value -> assertThat(String.valueOf(value)).contains("完整报告内容"));
        assertThat(fixture.service().resource("tenant-a", artifactId, "visualization")).isEmpty();
        assertThat(fixture.service().resource("tenant-a", artifactId, "citations")).isPresent();
        assertThat(fixture.service().resource("tenant-a", artifactId, "evidence-premises")).isPresent();
        assertThat(fixture.service().manifest("tenant-b", artifactId)).isEmpty();

        UiArtifactEntity metadata = fixture.entities().get(artifactId);
        assertThat(metadata.getStoreType()).isEqualTo("local");
        assertThat(metadata.getResourceCount()).isEqualTo(3);
        assertThat(metadata.getTotalBytes()).isPositive();
        assertThat(metadata.getCreatedAt()).isNotNull();
        assertThat(metadata.getUpdatedAt()).isNotNull();
        assertThat(metadata.getExpiresAt()).isNotNull();
    }

    @Test
    void prefersExplicitHtmlAndPersistsItAsAnHtmlFile() {
        Fixture fixture = fixture(64);
        String html = "<section class=\"custom-report\"><h1>Dynamic report</h1></section>";

        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-html", Map.of(
                "answer", "",
                "reportHtml", html,
                "status", "SUCCESS"
            ));

        String artifactId = String.valueOf(presentation.reference().get("artifactId"));
        Map<String, Object> manifest = fixture.service().manifest("tenant-a", artifactId).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) manifest.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) resources.get("report");

        assertThat(presentation.reference()).containsEntry("renderMode", "html");
        assertThat(report)
            .containsEntry("mediaType", "text/html")
            .containsEntry("objectKey", "resources/report.html");
        assertThat(fixture.service().resource("tenant-a", artifactId, "report")).contains(html);
        assertThat(resources).doesNotContainKey("answer");
    }

    @Test
    void prefersCanonicalMarkdownAnswerOverGeneratedReportHtml() {
        Fixture fixture = fixture(64);

        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-markdown", Map.of(
                "answer", "# Markdown report\n\n| name | value |\n|---|---:|\n| alpha | 42 |",
                "reportHtml", "<p>| name | value |<br>|---|---:|<br>| alpha | 42 |</p>",
                "status", "SUCCESS"
            ));

        String artifactId = String.valueOf(presentation.reference().get("artifactId"));
        assertThat(presentation.reference()).containsEntry("renderMode", "markdown");
        assertThat(fixture.service().resource("tenant-a", artifactId, "answer"))
            .hasValueSatisfying(value -> assertThat(String.valueOf(value)).contains("| alpha | 42 |"));
        assertThat(fixture.service().resource("tenant-a", artifactId, "report")).isEmpty();
    }

    @Test
    void deletesArtifactObjectsAndMarksLifecycleMetadata() {
        Fixture fixture = fixture(32);
        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-delete", Map.of("answer", "报告".repeat(50), "status", "SUCCESS"));
        String artifactId = String.valueOf(presentation.reference().get("artifactId"));

        assertThat(fixture.service().delete("tenant-a", artifactId)).isTrue();
        assertThat(fixture.service().manifest("tenant-a", artifactId)).isEmpty();
        assertThat(fixture.entities().get(artifactId).getStatus()).isEqualTo("DELETED");
        assertThat(fixture.service().delete("tenant-a", artifactId)).isFalse();
    }

    @Test
    void expiresDueArtifactsAndRemovesTheirBlobObjects() {
        Fixture fixture = fixture(32);
        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-expire", Map.of("answer", "报告".repeat(50), "status", "SUCCESS"));
        String artifactId = String.valueOf(presentation.reference().get("artifactId"));
        fixture.entities().get(artifactId).setExpiresAt(Instant.now().minusSeconds(1));

        fixture.service().expireDueArtifacts();

        assertThat(fixture.entities().get(artifactId).getStatus()).isEqualTo("EXPIRED");
        assertThat(fixture.service().manifest("tenant-a", artifactId)).isEmpty();
    }

    @Test
    void externalizesSmallUiResponseWhenAlwaysExternalizeIsEnabled() {
        Fixture fixture = fixture(10_000);
        Map<String, Object> response = Map.of("answer", "短回答", "status", "SUCCESS");

        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-2", response);

        assertThat(presentation.externalized()).isTrue();
        assertThat(presentation.uiResponse()).containsKey("uiArtifact");
        assertThat(presentation.reference()).containsKey("artifactId");
        assertThat(fixture.entities()).hasSize(1);
    }

    @Test
    void canRetainThresholdModeWhenAlwaysExternalizeIsDisabled() {
        Fixture fixture = fixture(10_000, false);
        Map<String, Object> response = Map.of("answer", "短回答", "status", "SUCCESS");

        UiArtifactService.Presentation presentation = fixture.service().externalizeIfNeeded(
            "tenant-a", "task-threshold", response);

        assertThat(presentation.externalized()).isFalse();
        assertThat(presentation.uiResponse()).isEqualTo(response);
        assertThat(presentation.reference()).isNull();
        assertThat(fixture.entities()).isEmpty();
    }

    private Fixture fixture(int threshold) {
        return fixture(threshold, true);
    }

    private Fixture fixture(int threshold, boolean alwaysExternalize) {
        UiArtifactProperties properties = properties(threshold);
        properties.setAlwaysExternalize(alwaysExternalize);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, UiArtifactEntity> entities = new LinkedHashMap<>();
        UiArtifactRepository repository = mock(UiArtifactRepository.class);
        when(repository.save(any(UiArtifactEntity.class))).thenAnswer(invocation -> {
            UiArtifactEntity entity = invocation.getArgument(0);
            entities.put(entity.getArtifactId(), entity);
            return entity;
        });
        when(repository.findByArtifactIdAndTenantId(any(), any())).thenAnswer(invocation -> {
            UiArtifactEntity entity = entities.get(invocation.getArgument(0, String.class));
            String tenantId = invocation.getArgument(1, String.class);
            return entity != null && tenantId.equals(entity.getTenantId()) ? Optional.of(entity) : Optional.empty();
        });
        when(repository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
            .thenAnswer(invocation -> {
                String status = invocation.getArgument(0, String.class);
                Instant before = invocation.getArgument(1, Instant.class);
                return entities.values().stream()
                    .filter(entity -> status.equals(entity.getStatus()))
                    .filter(entity -> entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(before))
                    .toList();
            });
        ArtifactBlobStore blobStore = new FilesystemArtifactBlobStore(objectMapper, properties);
        return new Fixture(
            new UiArtifactService(blobStore, repository, properties, objectMapper),
            entities
        );
    }

    private UiArtifactProperties properties(int threshold) {
        UiArtifactProperties properties = new UiArtifactProperties();
        properties.setStoragePath(temporaryDirectory.toString());
        properties.setExternalizeThresholdBytes(threshold);
        properties.setAnswerPreviewCharacters(32);
        properties.setTtlSeconds(3600);
        return properties;
    }

    private record Fixture(UiArtifactService service, Map<String, UiArtifactEntity> entities) {
    }
}
