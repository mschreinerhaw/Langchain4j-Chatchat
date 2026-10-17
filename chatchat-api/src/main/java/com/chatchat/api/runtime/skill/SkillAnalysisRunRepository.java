package com.chatchat.api.runtime.skill;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillAnalysisRunRepository extends JpaRepository<SkillAnalysisRunEntity,String> {
    Optional<SkillAnalysisRunEntity> findByIdAndTenantIdAndUserId(String id,String tenant,String user);
}

