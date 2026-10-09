package com.chatchat.enterprise.entity.mcp;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "capability_registry")
@Getter @Setter
public class CapabilityRegistryEntry {
    @Id @Column(length = 80) private String capabilityId;
    @Column(nullable = false, length = 64) private String serviceId;
    @Column(nullable = false, length = 256) private String toolName;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false, length = 64) private String currentVersion;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long lockVersion;
}
