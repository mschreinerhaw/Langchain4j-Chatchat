package com.chatchat.enterprise.repository.identity;

import com.chatchat.enterprise.entity.identity.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysMenuRepository extends JpaRepository<SysMenu, String> {
    List<SysMenu> findAllByOrderBySortOrderAscMenuNameAsc();

    List<SysMenu> findByParentId(String parentId);

    Optional<SysMenu> findByMenuCode(String menuCode);
}
