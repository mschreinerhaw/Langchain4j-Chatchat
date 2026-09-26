package com.chatchat.chat.skills.federation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpSkillSourceJpaRepository extends JpaRepository<McpSkillSourceEntity, String> {
    List<McpSkillSourceEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    Optional<McpSkillSourceEntity> findByIdAndTenantId(String id, String tenantId);
}
