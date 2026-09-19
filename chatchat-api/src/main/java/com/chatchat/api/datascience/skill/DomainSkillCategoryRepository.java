package com.chatchat.api.datascience.skill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DomainSkillCategoryRepository extends JpaRepository<DomainSkillCategoryEntity, String> {
    List<DomainSkillCategoryEntity> findByTenantIdOrderByNameAsc(String tenantId);

    Optional<DomainSkillCategoryEntity> findByTenantIdAndNameIgnoreCase(String tenantId, String name);
}
