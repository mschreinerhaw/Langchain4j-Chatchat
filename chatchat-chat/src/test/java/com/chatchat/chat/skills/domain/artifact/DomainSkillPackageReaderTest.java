package com.chatchat.chat.skills.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainSkillPackageReaderTest {
    @Test
    void exposesBundleResourcesAndParsesRequirementsWithoutGrantingThem() throws Exception {
        DomainSkillSourceArtifactRepository repository = mock(DomainSkillSourceArtifactRepository.class);
        DomainSkillSourceArtifactEntity source = new DomainSkillSourceArtifactEntity();
        source.setSourceType("ZIP");
        source.setOriginalArtifact(zip(
            "SKILL.md", "# Install",
            "references/install.md", "verified steps",
            "scripts/check.py", "print('ok')"));
        source.setParsedDocumentJson("""
            {"metadata":{"frontMatter":{"allowed_documents":["doc-1"],
            "allowed_mcp":["search"],"allowed_agents":["reviewer"],
            "workflows":["document-install"]}}}
            """);
        when(repository.findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc("skill-1", "tenant-a"))
            .thenReturn(Optional.of(source));
        DomainSkillPackageReader reader = new DomainSkillPackageReader(repository, new ObjectMapper());

        var view = reader.read("tenant-a", "skill-1");

        assertThat(view.requirements().documentIds()).containsExactly("doc-1");
        assertThat(view.requirements().mcpToolIds()).containsExactly("search");
        assertThat(view.requirements().agentIds()).containsExactly("reviewer");
        assertThat(view.requirements().workflowIds()).containsExactly("document-install");
        assertThat(view.resources()).extracting(item -> item.resourceId())
            .containsExactly("references/install.md", "scripts/check.py");
        assertThat(new String(reader.readResource("tenant-a", "skill-1", "references/install.md")
            .orElseThrow().content(), StandardCharsets.UTF_8)).isEqualTo("verified steps");
    }

    @Test
    void rejectsMalformedResourceDeclarationsInsteadOfSilentlyDroppingThem() {
        DomainSkillSourceArtifactRepository repository = mock(DomainSkillSourceArtifactRepository.class);
        DomainSkillSourceArtifactEntity source = new DomainSkillSourceArtifactEntity();
        source.setSourceType("MARKDOWN");
        source.setParsedDocumentJson("{not-json");
        when(repository.findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc("skill-1", "tenant-a"))
            .thenReturn(Optional.of(source));

        DomainSkillPackageReader reader = new DomainSkillPackageReader(repository, new ObjectMapper());

        assertThatThrownBy(() -> reader.read("tenant-a", "skill-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unable to read Skill resource declarations");
    }

    private byte[] zip(String... values) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (int index = 0; index < values.length; index += 2) {
                zip.putNextEntry(new ZipEntry(values[index]));
                zip.write(values[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
