package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysPermissionRepository extends JpaRepository<SysPermission, String> {
    /**
     * Finds the all by order by sort order asc permission name asc.
     *
     * @return the matching all by order by sort order asc permission name asc
     */
    List<SysPermission> findAllByOrderBySortOrderAscPermissionNameAsc();

    /**
     * Finds the by permission code.
     *
     * @param permissionCode the permission code value
     * @return the matching by permission code
     */
    Optional<SysPermission> findByPermissionCode(String permissionCode);
}
