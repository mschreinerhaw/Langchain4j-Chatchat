package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysOrg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysOrgRepository extends JpaRepository<SysOrg, String> {
    /**
     * Finds the by tenant id order by sort order asc org name asc.
     *
     * @param tenantId the tenant id value
     * @return the matching by tenant id order by sort order asc org name asc
     */
    List<SysOrg> findByTenantIdOrderBySortOrderAscOrgNameAsc(String tenantId);

    /**
     * Finds the by tenant id and parent id order by sort order asc org name asc.
     *
     * @param tenantId the tenant id value
     * @param parentId the parent id value
     * @return the matching by tenant id and parent id order by sort order asc org name asc
     */
    List<SysOrg> findByTenantIdAndParentIdOrderBySortOrderAscOrgNameAsc(String tenantId, String parentId);

    /**
     * Finds the by tenant id and org code.
     *
     * @param tenantId the tenant id value
     * @param orgCode the org code value
     * @return the matching by tenant id and org code
     */
    Optional<SysOrg> findByTenantIdAndOrgCode(String tenantId, String orgCode);
}
