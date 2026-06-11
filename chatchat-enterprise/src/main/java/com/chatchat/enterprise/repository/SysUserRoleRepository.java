package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysUserRoleRepository extends JpaRepository<SysUserRole, String> {
    /**
     * Finds the by user id.
     *
     * @param userId the user id value
     * @return the matching by user id
     */
    List<SysUserRole> findByUserId(String userId);

    /**
     * Finds the by role id.
     *
     * @param roleId the role id value
     * @return the matching by role id
     */
    List<SysUserRole> findByRoleId(String roleId);

    /**
     * Deletes the by user id.
     *
     * @param userId the user id value
     */
    void deleteByUserId(String userId);

    /**
     * Deletes the by role id.
     *
     * @param roleId the role id value
     */
    void deleteByRoleId(String roleId);
}
