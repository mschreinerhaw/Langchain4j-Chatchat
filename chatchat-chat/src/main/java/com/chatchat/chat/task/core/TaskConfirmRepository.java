package com.chatchat.chat.task.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskConfirmRepository extends JpaRepository<TaskConfirmEntity, String> {

    Optional<TaskConfirmEntity> findTopByTaskIdOrderByCreatedAtDesc(String taskId);

    List<TaskConfirmEntity> findByStatusAndExpiredAtBeforeOrderByExpiredAtAsc(String status, Instant expiredAt);

    long countByTaskIdAndStatusIn(String taskId, Collection<String> statuses);
}
