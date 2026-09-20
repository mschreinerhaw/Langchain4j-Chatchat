package com.chatchat.chat.skills.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DomainSkillImportTaskRepository extends JpaRepository<DomainSkillImportTaskEntity, String> {
    Optional<DomainSkillImportTaskEntity> findByIdAndTenantId(String id, String tenantId);
    List<DomainSkillImportTaskEntity> findByStatusIn(Collection<String> statuses);
}
