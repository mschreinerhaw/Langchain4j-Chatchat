package com.chatchat.api.runtime.coding;

import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.skills.domain.adapter.ExternalSkillAdapterGateway;
import com.chatchat.chat.skills.domain.artifact.*;
import com.chatchat.chat.skills.domain.catalog.*;
import com.chatchat.chat.skills.domain.indexing.DomainSkillIndexService;
import com.chatchat.chat.skills.domain.source.DomainSkillRemoteImporter;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes=NativeSkillPersistenceTest.Config.class)
class NativeSkillPersistenceTest {
    @Configuration
    @EntityScan(basePackageClasses={DomainSkillEntity.class,DomainSkillSourceArtifactEntity.class})
    @EnableJpaRepositories(basePackageClasses={DomainSkillRepository.class,DomainSkillSourceArtifactRepository.class})
    @Import({DomainSkillService.class,DomainSkillArtifactStore.class,DomainSkillPackageReader.class,com.fasterxml.jackson.databind.ObjectMapper.class})
    static class Config {}
    @Autowired DomainSkillService service;
    @Autowired DomainSkillRepository skills;
    @Autowired DomainSkillSourceArtifactRepository originals;
    @Autowired EntityManager entityManager;
    @MockBean ExternalSkillAdapterGateway compiler;
    @MockBean DomainSkillIndexService index;
    @MockBean DomainSkillRemoteImporter remote;
    @MockBean McpLicenseEntitlementPort entitlement;
    final String raw="---\nname: native-persist\ndescription: Preserve original data instructions\nlicense: MIT\n---\n# Original\r\nDo not rewrite.\n";

    @Test void nativeImportAndFailedCompileToggleKeepCreationTimestampAndOriginalBytes() {
        var imported=service.importFile("tenant","admin",raw.getBytes(StandardCharsets.UTF_8),"SKILL.md","","General",
            new DomainSkillService.ImportOptions(false,"NATIVE"));
        skills.flush(); entityManager.clear();
        var before=skills.findById(imported.getId()).orElseThrow(); String sourceId=before.getNativeSourceId();
        assertThat(before.getCreatedAt()).isNotNull(); assertThat(before.getMarkdownContent()).isEqualTo(raw);
        var original=originals.findById(sourceId).orElseThrow();
        assertThat(original.getOriginalArtifact()).isEqualTo(raw.getBytes(StandardCharsets.UTF_8));
        when(compiler.adaptAndCompile(any())).thenThrow(new IllegalArgumentException("Compiler offline"));
        service.save("tenant","admin",new DomainSkillService.SaveSkillCommand(before.getId(),before.getName(),"General","",raw,true));
        skills.flush(); entityManager.clear();
        var after=skills.findById(before.getId()).orElseThrow();
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt()); assertThat(after.getNativeSourceId()).isEqualTo(sourceId);
        assertThat(after.getCompilationStatus()).isEqualTo("FAILED"); assertThat(after.getMarkdownContent()).isEqualTo(raw);
        assertThat(originals.findById(sourceId).orElseThrow().getOriginalArtifact()).isEqualTo(raw.getBytes(StandardCharsets.UTF_8));
    }
    @Test void uncompiledSystemImportPersistsOriginalWithoutCallingCompiler() {
        var imported=service.importFile("tenant","admin","# Original".getBytes(StandardCharsets.UTF_8),"SKILL.md","Example","General",
            new DomainSkillService.ImportOptions(false,"RUNTIME"));
        skills.flush(); entityManager.clear();
        var stored=skills.findById(imported.getId()).orElseThrow();assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getRuntimeCompilationEnabled()).isFalse();assertThat(stored.getMarkdownContent()).isEqualTo("# Original");
        assertThat(originals.findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(stored.getId(),"tenant")).isPresent();
        verifyNoInteractions(compiler);
    }
    @Test void newEditorCompilationFailureStillPersistsOriginalDraftAndCreationTime() {
        when(compiler.adaptAndCompile(any())).thenThrow(new IllegalArgumentException("Compiler offline"));
        var saved=service.save("tenant","admin",new DomainSkillService.SaveSkillCommand(null,"System draft","General","","# Original editor input",true));
        skills.flush(); entityManager.clear();
        var stored=skills.findById(saved.getId()).orElseThrow();
        assertThat(stored.getCreatedAt()).isNotNull(); assertThat(stored.getStatus()).isEqualTo("DRAFT");
        assertThat(stored.getCompilationStatus()).isEqualTo("FAILED"); assertThat(stored.getMarkdownContent()).isEqualTo("# Original editor input");
    }
}
