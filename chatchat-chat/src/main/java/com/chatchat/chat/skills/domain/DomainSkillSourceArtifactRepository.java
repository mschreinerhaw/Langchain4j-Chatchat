package com.chatchat.chat.skills.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainSkillSourceArtifactRepository extends JpaRepository<DomainSkillSourceArtifactEntity, String> {
    void deleteBySkillIdAndTenantId(String skillId, String tenantId);
}
