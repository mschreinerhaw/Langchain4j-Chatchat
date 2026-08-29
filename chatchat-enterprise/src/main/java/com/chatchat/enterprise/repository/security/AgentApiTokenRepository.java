package com.chatchat.enterprise.repository.security;

import com.chatchat.enterprise.entity.security.AgentApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentApiTokenRepository extends JpaRepository<AgentApiToken, String> {

    Optional<AgentApiToken> findByTokenHash(String tokenHash);

    List<AgentApiToken> findAllByOrderByCreatedAtDesc();

    List<AgentApiToken> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AgentApiToken token
           set token.lastUsedAt = :usedAt,
               token.lastUsedIp = :ipAddress,
               token.lastUsedPath = :requestPath,
               token.usedCount = token.usedCount + 1
         where token.id = :tokenId
        """)
    int recordUse(@Param("tokenId") String tokenId,
                  @Param("usedAt") Instant usedAt,
                  @Param("ipAddress") String ipAddress,
                  @Param("requestPath") String requestPath);
}
