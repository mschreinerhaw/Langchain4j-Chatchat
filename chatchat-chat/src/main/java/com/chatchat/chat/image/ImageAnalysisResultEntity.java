package com.chatchat.chat.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "image_analysis_result",
    indexes = {
        @Index(name = "idx_image_analysis_file", columnList = "file_id, created_at"),
        @Index(name = "idx_image_analysis_tenant", columnList = "tenant_id, created_at"),
        @Index(name = "idx_image_analysis_type", columnList = "tenant_id, image_type")
    }
)
public class ImageAnalysisResultEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "file_id", length = 64, nullable = false)
    private String fileId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(length = 2000)
    private String question;

    @Column(length = 32)
    private String mode = "auto";

    @Column(name = "image_type", length = 32)
    private String imageType = "screenshot";

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "structured_data_json", columnDefinition = "TEXT")
    private String structuredDataJson;

    @Column
    private Double confidence;

    @Column(name = "analysis_source", length = 64)
    private String analysisSource = "rule";

    @Column(length = 32, nullable = false)
    private String status = "COMPLETED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
