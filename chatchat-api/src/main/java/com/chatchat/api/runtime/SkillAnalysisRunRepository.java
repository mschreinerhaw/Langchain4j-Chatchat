package com.chatchat.api.runtime;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface SkillAnalysisRunRepository extends JpaRepository<SkillAnalysisRunEntity,String> {
    Optional<SkillAnalysisRunEntity> findByIdAndTenantIdAndUserId(String id,String tenant,String user);
}

