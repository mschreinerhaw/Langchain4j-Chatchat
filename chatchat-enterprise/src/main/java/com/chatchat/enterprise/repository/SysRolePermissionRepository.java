package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, String> {
    List<SysRolePermission> findByRoleId(String roleId);

    void deleteByRoleId(String roleId);

    void deleteByPermissionId(String permissionId);
}
