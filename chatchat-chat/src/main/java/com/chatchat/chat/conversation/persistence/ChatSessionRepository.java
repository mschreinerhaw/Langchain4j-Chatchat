package com.chatchat.chat.conversation.persistence;

import com.chatchat.chat.conversation.model.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /**
     * Finds the by user id order by updated at desc.
     *
     * @param userId the user id value
     * @return the matching by user id order by updated at desc
     */
    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    @Query("select s from ChatSessionEntity s where s.userId = :userId "
        + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' order by s.updatedAt desc")
    List<ChatSessionEntity> findPlatformByUserIdOrderByUpdatedAtDesc(@Param("userId") String userId);

    List<ChatSessionEntity> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    @Query("select s from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
        + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' order by s.updatedAt desc")
    List<ChatSessionEntity> findPlatformByTenantIdAndUserIdOrderByUpdatedAtDesc(
        @Param("tenantId") String tenantId, @Param("userId") String userId);

    List<ChatSessionEntity> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);

    @Query("select s from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
        + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' order by s.updatedAt desc")
    List<ChatSessionEntity> findPlatformByTenantIdAndUserIdOrderByUpdatedAtDesc(
        @Param("tenantId") String tenantId, @Param("userId") String userId, Pageable pageable);

    Page<ChatSessionEntity> findPageByTenantIdAndUserIdOrderByUpdatedAtDesc(
        String tenantId,
        String userId,
        Pageable pageable
    );

    @Query(value = "select s from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
        + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' order by s.updatedAt desc",
        countQuery = "select count(s) from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
            + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI'")
    Page<ChatSessionEntity> findPlatformPageByTenantIdAndUserIdOrderByUpdatedAtDesc(
        @Param("tenantId") String tenantId, @Param("userId") String userId, Pageable pageable);

    Page<ChatSessionEntity> findPageByTenantIdAndUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
        String tenantId,
        String userId,
        String title,
        Pageable pageable
    );

    @Query(value = "select s from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
        + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' "
        + "and lower(s.title) like lower(concat('%', :title, '%')) order by s.updatedAt desc",
        countQuery = "select count(s) from ChatSessionEntity s where s.tenantId = :tenantId and s.userId = :userId "
            + "and coalesce(s.sourceChannel, 'PLATFORM_UI') = 'PLATFORM_UI' "
            + "and lower(s.title) like lower(concat('%', :title, '%'))")
    Page<ChatSessionEntity> findPlatformPageByTenantIdAndUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
        @Param("tenantId") String tenantId, @Param("userId") String userId,
        @Param("title") String title, Pageable pageable);

    java.util.Optional<ChatSessionEntity> findBySessionIdAndTenantId(String sessionId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSessionEntity session where session.sessionId = :sessionId")
    java.util.Optional<ChatSessionEntity> findLockedBySessionId(@Param("sessionId") String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from ChatSessionEntity session
         where session.sessionId = :sessionId
           and session.tenantId = :tenantId
        """)
    java.util.Optional<ChatSessionEntity> findLockedBySessionIdAndTenantId(
        @Param("sessionId") String sessionId,
        @Param("tenantId") String tenantId
    );
}
