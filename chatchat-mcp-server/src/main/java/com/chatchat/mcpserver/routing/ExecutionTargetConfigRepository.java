package com.chatchat.mcpserver.routing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionTargetConfigRepository extends JpaRepository<ExecutionTargetConfig, String> {

    List<ExecutionTargetConfig> findByEnabledTrueOrderByPriorityAscTargetKeyAsc();

    List<ExecutionTargetConfig> findByAssetTypeAndEnabledTrueOrderByPriorityAscTargetKeyAsc(String assetType);

    Optional<ExecutionTargetConfig> findByTargetKeyIgnoreCase(String targetKey);
}
