package com.chatchat.enterprise.repository.identity;

import com.chatchat.enterprise.entity.identity.SysMenuPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SysMenuPermissionRepository extends JpaRepository<SysMenuPermission, String> {
    List<SysMenuPermission> findByMenuId(String menuId);

    List<SysMenuPermission> findByPermissionIdIn(Collection<String> permissionIds);

    Optional<SysMenuPermission> findByMenuIdAndPermissionId(String menuId, String permissionId);

    void deleteByMenuId(String menuId);

    void deleteByPermissionId(String permissionId);
}
