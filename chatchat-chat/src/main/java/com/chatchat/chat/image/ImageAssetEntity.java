package com.chatchat.chat.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "image_asset",
    indexes = {
        @Index(name = "idx_image_asset_tenant", columnList = "tenant_id, created_at"),
        @Index(name = "idx_image_asset_user", columnList = "tenant_id, user_id")
    }
)
public class ImageAssetEntity {

    @Id
    @Column(name = "file_id", length = 64, nullable = false)
    private String fileId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "original_file_name", length = 512)
    private String originalFileName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "file_path", length = 1000, nullable = false)
    private String filePath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "sha256", length = 128)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (fileId == null || fileId.isBlank()) {
            fileId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
    }
}
