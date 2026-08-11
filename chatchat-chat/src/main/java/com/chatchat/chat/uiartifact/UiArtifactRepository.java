package com.chatchat.chat.uiartifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UiArtifactRepository extends JpaRepository<UiArtifactEntity, String> {

    Optional<UiArtifactEntity> findByArtifactIdAndTenantId(String artifactId, String tenantId);

    List<UiArtifactEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(String status, Instant expiresAt);
}
