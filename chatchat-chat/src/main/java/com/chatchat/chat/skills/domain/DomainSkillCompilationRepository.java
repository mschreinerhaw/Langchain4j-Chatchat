package com.chatchat.chat.skills.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainSkillCompilationRepository extends JpaRepository<DomainSkillCompilationEntity, String> {
    void deleteBySkillIdAndTenantId(String skillId, String tenantId);
}
