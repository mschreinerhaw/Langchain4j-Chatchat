package com.chatchat.chat.runtime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AgentRuntimeRunRepository extends JpaRepository<AgentRuntimeRunEntity, String> {

    List<AgentRuntimeRunEntity> findByStatusInAndFinishedAtLessThanEqualOrderByFinishedAtAsc(
        Collection<String> statuses, Long cutoff, Pageable pageable);

    List<AgentRuntimeRunEntity> findByStatusInOrderByUpdatedAtAsc(
        Collection<String> statuses, Pageable pageable);

    @Query("""
        select r from AgentRuntimeRunEntity r
        where (:status = '' or r.status = :status)
          and (:tenantId = '' or r.tenantId = :tenantId)
          and (:userId = '' or r.userId = :userId)
          and (:conversationId = '' or r.conversationId = :conversationId)
        order by r.updatedAt desc
        """)
    Page<AgentRuntimeRunEntity> search(
        @Param("status") String status,
        @Param("tenantId") String tenantId,
        @Param("userId") String userId,
        @Param("conversationId") String conversationId,
        Pageable pageable
    );
}
