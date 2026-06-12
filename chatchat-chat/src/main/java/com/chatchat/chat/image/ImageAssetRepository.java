package com.chatchat.chat.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageAssetRepository extends JpaRepository<ImageAssetEntity, String> {

    List<ImageAssetEntity> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
