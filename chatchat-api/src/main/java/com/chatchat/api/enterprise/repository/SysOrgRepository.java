package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.SysOrg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysOrgRepository extends JpaRepository<SysOrg, String> {
    List<SysOrg> findByTenantIdOrderBySortOrderAscOrgNameAsc(String tenantId);

    List<SysOrg> findByTenantIdAndParentIdOrderBySortOrderAscOrgNameAsc(String tenantId, String parentId);
}
