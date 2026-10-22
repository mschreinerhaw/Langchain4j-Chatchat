package com.chatchat.mcpserver.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, String> {

    Optional<AdminUser> findByUsernameIgnoreCase(String username);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from AdminUser u where lower(u.username)=lower(:username)")
    Optional<AdminUser> lockByUsername(@org.springframework.data.repository.query.Param("username") String username);
}
