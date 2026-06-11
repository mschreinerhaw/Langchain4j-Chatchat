package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRoleOrgScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRoleOrgScopeRepository extends JpaRepository<SysRoleOrgScope, String> {
    /**
     * Finds the by role id order by scope type asc org id asc.
     *
     * @param roleId the role id value
     * @return the matching by role id order by scope type asc org id asc
     */
    List<SysRoleOrgScope> findByRoleIdOrderByScopeTypeAscOrgIdAsc(String roleId);

    /**
     * Deletes the by role id.
     *
     * @param roleId the role id value
     */
    void deleteByRoleId(String roleId);
}
