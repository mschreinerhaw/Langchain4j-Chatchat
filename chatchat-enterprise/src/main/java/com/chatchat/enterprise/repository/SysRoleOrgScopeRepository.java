package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRoleOrgScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRoleOrgScopeRepository extends JpaRepository<SysRoleOrgScope, String> {
    List<SysRoleOrgScope> findByRoleIdOrderByScopeTypeAscOrgIdAsc(String roleId);

    void deleteByRoleId(String roleId);
}
