package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysPermissionRepository extends JpaRepository<SysPermission, String> {
    List<SysPermission> findAllByOrderBySortOrderAscPermissionNameAsc();

    Optional<SysPermission> findByPermissionCode(String permissionCode);
}
