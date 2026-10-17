package com.chatchat.api.runtime.skill;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillDataBindingRepository extends JpaRepository<SkillDataBindingEntity,String> {
    Optional<SkillDataBindingEntity> findByTenantIdAndDomainSkillIdAndContractId(String tenant,String skill,String contract);
    List<SkillDataBindingEntity> findByTenantIdAndDomainSkillId(String tenant,String skill);
}

