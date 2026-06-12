package com.chatchat.mcpserver.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SshHostConfigRepository extends JpaRepository<SshHostConfig, String> {

    List<SshHostConfig> findByEnabledTrueOrderByNameAsc();

    Optional<SshHostConfig> findByToolNameIgnoreCase(String toolName);
}
