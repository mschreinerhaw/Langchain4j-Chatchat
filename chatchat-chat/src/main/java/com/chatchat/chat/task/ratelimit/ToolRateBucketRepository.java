package com.chatchat.chat.task.ratelimit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ToolRateBucketRepository extends JpaRepository<ToolRateBucketEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ToolRateBucketEntity b where b.bucketId = :bucketId")
    Optional<ToolRateBucketEntity> findForUpdate(@Param("bucketId") String bucketId);

    @Modifying
    @Query("delete from ToolRateBucketEntity b where b.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
