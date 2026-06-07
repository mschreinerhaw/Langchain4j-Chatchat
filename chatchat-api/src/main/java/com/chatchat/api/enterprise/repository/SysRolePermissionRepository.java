package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, String> {
    List<SysRolePermission> findByRoleId(String roleId);

    void deleteByRoleId(String roleId);

    void deleteByPermissionId(String permissionId);
}
