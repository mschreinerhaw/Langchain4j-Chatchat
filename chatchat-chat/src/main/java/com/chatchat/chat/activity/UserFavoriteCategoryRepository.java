package com.chatchat.chat.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteCategoryRepository extends JpaRepository<UserFavoriteCategoryEntity, String> {

    List<UserFavoriteCategoryEntity> findByTenantIdAndUserIdOrderByCreatedAtAsc(String tenantId, String userId);

    Optional<UserFavoriteCategoryEntity> findByTenantIdAndUserIdAndCategoryName(
        String tenantId,
        String userId,
        String categoryName
    );
}
