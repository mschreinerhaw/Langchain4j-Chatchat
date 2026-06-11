package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, String> {
    /**
     * Finds the by username.
     *
     * @param username the username value
     * @return the matching by username
     */
    Optional<SysUser> findByUsername(String username);

    /**
     * Finds the by tenant id order by username asc.
     *
     * @param tenantId the tenant id value
     * @return the matching by tenant id order by username asc
     */
    List<SysUser> findByTenantIdOrderByUsernameAsc(String tenantId);
}
