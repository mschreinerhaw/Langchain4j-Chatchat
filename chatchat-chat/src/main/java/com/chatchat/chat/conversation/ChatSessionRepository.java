package com.chatchat.chat.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /**
     * Finds the by user id order by updated at desc.
     *
     * @param userId the user id value
     * @return the matching by user id order by updated at desc
     */
    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<ChatSessionEntity> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    List<ChatSessionEntity> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);

    java.util.Optional<ChatSessionEntity> findBySessionIdAndTenantId(String sessionId, String tenantId);
}
