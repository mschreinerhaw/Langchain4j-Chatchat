package com.chatchat.enterprise.repository.security;

import com.chatchat.enterprise.entity.security.SkillResourceScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillResourceScopeRepository extends JpaRepository<SkillResourceScope, String> {
    List<SkillResourceScope> findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(String tenantId, String skillId);
}
