package com.chatchat.integration.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_capability", uniqueConstraints = @UniqueConstraint(name = "uk_mcp_capability_code", columnNames = "capability_code"))
public class McpCapability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "capability_code", nullable = false, length = 64)
    private String capabilityCode;
    @Column(name = "capability_name", nullable = false, length = 128)
    private String capabilityName;
    @Column(name = "capability_type", nullable = false, length = 32)
    private String capabilityType;
    @Column(name = "provider_type", nullable = false, length = 32)
    private String providerType;
    @Column(name = "provider_module", length = 128)
    private String providerModule;
    @Column(length = 1000)
    private String description;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
