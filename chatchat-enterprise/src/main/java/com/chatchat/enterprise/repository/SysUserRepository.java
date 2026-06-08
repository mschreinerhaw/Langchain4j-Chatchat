package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, String> {
    Optional<SysUser> findByUsername(String username);

    List<SysUser> findByTenantIdOrderByUsernameAsc(String tenantId);
}
