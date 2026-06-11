package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, String> {
    /**
     * Finds the by tenant id order by name asc.
     *
     * @param tenantId the tenant id value
     * @return the matching by tenant id order by name asc
     */
    List<DataSourceConfig> findByTenantIdOrderByNameAsc(String tenantId);
}
