package com.chatchat.chat.skills.domain;

import com.chatchat.chat.skills.domain.adapter.ExternalSkillCompilation;
import com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Persists the original, parsed, and compiled forms without bloating skill-list queries. */
@Component
@RequiredArgsConstructor
public class DomainSkillArtifactStore {
    private final DomainSkillSourceArtifactRepository sourceRepository;
    private final DomainSkillCompilationRepository compilationRepository;

    public void store(String tenantId, String skillId, String sourceType, String fileName,
                      ExternalSkillCompilation compilation) {
        DomainSkillSourceArtifactEntity source = new DomainSkillSourceArtifactEntity();
        source.setId(UUID.randomUUID().toString());
        source.setTenantId(tenantId);
        source.setSkillId(skillId);
        source.setSourceType(sourceType);
        source.setSourceReference(trim(compilation.sourceReference(), 2000));
        source.setOriginalFileName(trim(fileName, 300));
        source.setOriginalHash(compilation.originalHash());
        source.setOriginalArtifact(compilation.originalArtifact());
        source.setParsedDocumentJson(compilation.parsedDocumentJson());
        source = sourceRepository.save(source);

        RuntimeSkillIr ir = compilation.skillIr();
        DomainSkillCompilationEntity compiled = new DomainSkillCompilationEntity();
        compiled.setId(UUID.randomUUID().toString());
        compiled.setTenantId(tenantId);
        compiled.setSkillId(skillId);
        compiled.setSourceId(source.getId());
        compiled.setIrSchemaVersion(ir.schemaVersion());
        compiled.setCompilerVersion(ir.compilation().compilerVersion());
        compiled.setCompilerModel(trim(ir.compilation().model(), 200));
        compiled.setCompilationMode(ir.compilationMode());
        compiled.setSkillIrJson(compilation.skillIrJson());
        compilationRepository.save(compiled);
    }

    public void delete(String tenantId, String skillId) {
        compilationRepository.deleteBySkillIdAndTenantId(skillId, tenantId);
        sourceRepository.deleteBySkillIdAndTenantId(skillId, tenantId);
    }

    private String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
