package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysTenantRepository extends JpaRepository<SysTenant, String> {
    /**
     * Finds the by tenant code.
     *
     * @param tenantCode the tenant code value
     * @return the matching by tenant code
     */
    Optional<SysTenant> findByTenantCode(String tenantCode);

    /**
     * Finds the all by order by tenant name asc.
     *
     * @return the matching all by order by tenant name asc
     */
    List<SysTenant> findAllByOrderByTenantNameAsc();
}
