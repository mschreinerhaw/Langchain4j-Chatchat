package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.SysTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysTenantRepository extends JpaRepository<SysTenant, String> {
    Optional<SysTenant> findByTenantCode(String tenantCode);

    List<SysTenant> findAllByOrderByTenantNameAsc();
}
