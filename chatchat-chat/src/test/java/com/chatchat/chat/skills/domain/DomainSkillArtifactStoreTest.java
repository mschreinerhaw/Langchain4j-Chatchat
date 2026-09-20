package com.chatchat.chat.skills.domain;

import com.chatchat.chat.skills.domain.adapter.ExternalSkillCompilation;
import com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainSkillArtifactStoreTest {
    @Test
    void storesOriginalParsedAndCompiledArtifactsSeparately() {
        DomainSkillSourceArtifactRepository sources = mock(DomainSkillSourceArtifactRepository.class);
        DomainSkillCompilationRepository compilations = mock(DomainSkillCompilationRepository.class);
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeSkillIr ir = new RuntimeSkillIr(RuntimeSkillIr.SCHEMA_VERSION, "xlsx", "Spreadsheet processing",
            "# Spreadsheet Processing", List.of("READ", "EDIT"), List.of("Preserve formulas"), "MODEL");
        byte[] original = "---\nname: xlsx\n---\n# Instructions".getBytes(StandardCharsets.UTF_8);
        ExternalSkillCompilation compilation = new ExternalSkillCompilation(ir, original, "sha256-value",
            "{\"name\":\"xlsx\"}", "{\"schemaVersion\":\"runtime_skill_ir.v1\"}",
            "https://example.test/xlsx/SKILL.md");

        new DomainSkillArtifactStore(sources, compilations)
            .store("tenant-a", "skill-1", "URL_MARKDOWN", "SKILL.md", compilation);

        ArgumentCaptor<DomainSkillSourceArtifactEntity> sourceCaptor =
            ArgumentCaptor.forClass(DomainSkillSourceArtifactEntity.class);
        verify(sources).save(sourceCaptor.capture());
        assertThat(sourceCaptor.getValue().getOriginalArtifact()).isEqualTo(original);
        assertThat(sourceCaptor.getValue().getOriginalHash()).isEqualTo("sha256-value");
        assertThat(sourceCaptor.getValue().getParsedDocumentJson()).contains("xlsx");

        ArgumentCaptor<DomainSkillCompilationEntity> compilationCaptor =
            ArgumentCaptor.forClass(DomainSkillCompilationEntity.class);
        verify(compilations).save(compilationCaptor.capture());
        assertThat(compilationCaptor.getValue().getSourceId()).isEqualTo(sourceCaptor.getValue().getId());
        assertThat(compilationCaptor.getValue().getIrSchemaVersion()).isEqualTo("runtime_skill_ir.v1");
        assertThat(compilationCaptor.getValue().getCompilerVersion()).isEqualTo(RuntimeSkillIr.COMPILER_VERSION);
        assertThat(compilationCaptor.getValue().getCompilationMode()).isEqualTo("MODEL");
        assertThat(compilationCaptor.getValue().getSkillIrJson()).contains("runtime_skill_ir.v1");
    }
}
