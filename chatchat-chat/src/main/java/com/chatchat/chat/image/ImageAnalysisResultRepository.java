package com.chatchat.chat.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageAnalysisResultRepository extends JpaRepository<ImageAnalysisResultEntity, String> {

    List<ImageAnalysisResultEntity> findByIdIn(List<String> ids);

    List<ImageAnalysisResultEntity> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<ImageAnalysisResultEntity> findByTenantIdAndFileIdOrderByCreatedAtDesc(String tenantId, String fileId);
}
