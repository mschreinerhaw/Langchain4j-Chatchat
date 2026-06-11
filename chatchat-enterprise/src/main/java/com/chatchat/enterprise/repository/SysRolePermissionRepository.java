package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, String> {
    /**
     * Finds the by role id.
     *
     * @param roleId the role id value
     * @return the matching by role id
     */
    List<SysRolePermission> findByRoleId(String roleId);

    /**
     * Deletes the by role id.
     *
     * @param roleId the role id value
     */
    void deleteByRoleId(String roleId);

    /**
     * Deletes the by permission id.
     *
     * @param permissionId the permission id value
     */
    void deleteByPermissionId(String permissionId);
}
