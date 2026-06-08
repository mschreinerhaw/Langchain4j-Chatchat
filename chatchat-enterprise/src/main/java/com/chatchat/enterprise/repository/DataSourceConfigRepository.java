package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, String> {
    List<DataSourceConfig> findByTenantIdOrderByNameAsc(String tenantId);
}
