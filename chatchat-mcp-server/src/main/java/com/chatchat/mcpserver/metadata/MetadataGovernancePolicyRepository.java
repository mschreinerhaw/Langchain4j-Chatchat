package com.chatchat.mcpserver.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MetadataGovernancePolicyRepository
    extends JpaRepository<MetadataGovernancePolicyEntity, String> {

    Optional<MetadataGovernancePolicyEntity> findByCode(String code);
    Optional<MetadataGovernancePolicyEntity> findFirstByEnabledTrueOrderByRevisionDesc();
}
