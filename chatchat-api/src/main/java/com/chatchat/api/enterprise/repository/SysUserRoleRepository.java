package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysUserRoleRepository extends JpaRepository<SysUserRole, String> {
    List<SysUserRole> findByUserId(String userId);

    List<SysUserRole> findByRoleId(String roleId);

    void deleteByUserId(String userId);

    void deleteByRoleId(String roleId);
}
