package com.chatchat.chat.dag;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.List;

public interface NodeAttemptRepository extends JpaRepository<NodeAttemptEntity, String> {

    Optional<NodeAttemptEntity> findTopByTenantIdAndRunIdAndNodeIdOrderByAttemptNumberDesc(
        String tenantId, String runId, Integer nodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NodeAttemptEntity> findByTenantIdAndAttemptId(String tenantId, String attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<NodeAttemptEntity> findAllByTenantIdAndAttemptIdInOrderByAttemptId(
        String tenantId, List<String> attemptIds);

    List<NodeAttemptEntity> findAllByTenantIdAndRunIdAndStateOrderByCommittedAtAscNodeIdAsc(
        String tenantId, String runId, String state);
}
