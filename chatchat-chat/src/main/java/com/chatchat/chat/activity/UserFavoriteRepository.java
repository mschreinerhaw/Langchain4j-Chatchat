package com.chatchat.chat.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, String> {

    Optional<UserFavoriteEntity> findByTenantIdAndUserIdAndTargetTypeAndTargetId(
        String tenantId,
        String userId,
        String targetType,
        String targetId
    );

    List<UserFavoriteEntity> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId, Pageable pageable);

    List<UserFavoriteEntity> findByTenantIdAndUserIdAndCategoryOrderByCreatedAtDesc(
        String tenantId,
        String userId,
        String category,
        Pageable pageable
    );

    List<UserFavoriteEntity> findByTenantIdAndUserIdAndTargetTypeOrderByCreatedAtDesc(
        String tenantId,
        String userId,
        String targetType,
        Pageable pageable
    );

    List<UserFavoriteEntity> findByTenantIdAndUserIdAndTargetTypeAndCategoryOrderByCreatedAtDesc(
        String tenantId,
        String userId,
        String targetType,
        String category,
        Pageable pageable
    );
}
