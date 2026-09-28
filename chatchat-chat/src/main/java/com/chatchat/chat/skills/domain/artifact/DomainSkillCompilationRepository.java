package com.chatchat.chat.skills.domain.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainSkillCompilationRepository extends JpaRepository<DomainSkillCompilationEntity, String> {
    java.util.Optional<DomainSkillCompilationEntity> findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(String skillId, String tenantId);
    void deleteBySkillIdAndTenantId(String skillId, String tenantId);
}
