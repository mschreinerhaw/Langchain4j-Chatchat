package com.chatchat.api.runtime;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SkillDataBindingRepository extends JpaRepository<SkillDataBindingEntity,String> {
    Optional<SkillDataBindingEntity> findByTenantIdAndDomainSkillIdAndContractId(String tenant,String skill,String contract);
    List<SkillDataBindingEntity> findByTenantIdAndDomainSkillId(String tenant,String skill);
}

