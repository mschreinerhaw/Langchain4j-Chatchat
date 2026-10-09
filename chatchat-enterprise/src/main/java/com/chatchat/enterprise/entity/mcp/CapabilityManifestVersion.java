package com.chatchat.enterprise.entity.mcp;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "capability_version", uniqueConstraints = @UniqueConstraint(
    name = "uk_capability_content_version", columnNames = {"capability_id", "content_hash"}))
@Getter @Setter
public class CapabilityManifestVersion {
    @Id @Column(length = 160) private String id;
    @Column(name = "capability_id", nullable = false, length = 80) private String capabilityId;
    @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
    @Column(nullable = false, length = org.hibernate.Length.LONG32) private String manifestJson;
    @Column(nullable = false) private Instant createdAt;
}
