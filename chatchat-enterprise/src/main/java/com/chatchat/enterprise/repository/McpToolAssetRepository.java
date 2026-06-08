package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.McpToolAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpToolAssetRepository extends JpaRepository<McpToolAsset, String> {
    Optional<McpToolAsset> findByLocalToolName(String localToolName);

    List<McpToolAsset> findByServiceIdOrderByLocalToolNameAsc(String serviceId);

    List<McpToolAsset> findAllByOrderByLocalToolNameAsc();
}
