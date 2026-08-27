package com.chatchat.mcpserver.ops.ssh;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SshHostConfigRepository extends JpaRepository<SshHostConfig, String> {

    List<SshHostConfig> findByEnabledTrueOrderByNameAsc();

    Optional<SshHostConfig> findByNameIgnoreCase(String name);

    Optional<SshHostConfig> findByToolNameIgnoreCase(String toolName);

    long countByCategoryId(String categoryId);
}
