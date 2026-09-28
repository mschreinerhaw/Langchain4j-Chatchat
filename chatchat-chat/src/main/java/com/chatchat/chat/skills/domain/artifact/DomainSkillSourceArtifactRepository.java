package com.chatchat.chat.skills.domain.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DomainSkillSourceArtifactRepository extends JpaRepository<DomainSkillSourceArtifactEntity, String> {
    Optional<DomainSkillSourceArtifactEntity> findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(
        String skillId, String tenantId);
    void deleteBySkillIdAndTenantId(String skillId, String tenantId);
}
