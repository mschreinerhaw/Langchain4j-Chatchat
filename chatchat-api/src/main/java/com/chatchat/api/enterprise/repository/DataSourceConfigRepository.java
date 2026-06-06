package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, String> {
    List<DataSourceConfig> findByTenantIdOrderByNameAsc(String tenantId);
}
