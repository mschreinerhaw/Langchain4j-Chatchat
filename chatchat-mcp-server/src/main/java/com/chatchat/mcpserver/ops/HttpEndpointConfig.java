package com.chatchat.mcpserver.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_ops_http_endpoint")
public class HttpEndpointConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 200)
    private String name;

    @Column(unique = true, length = 128)
    private String toolName;

    @Column(length = 200)
    private String title;

    @Column(length = 1200)
    private String description;

    @Column(nullable = false, length = 16)
    private String method = "GET";

    @Column(nullable = false, length = 2000)
    private String urlTemplate;

    @Lob
    @Column(columnDefinition = "longtext")
    private String headersJson;

    @Lob
    @Column(columnDefinition = "longtext")
    private String bodyTemplate;

    @Lob
    @Column(columnDefinition = "longtext")
    private String inputSchemaJson;

    @Lob
    @Column(columnDefinition = "longtext")
    private String governanceJson;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 32)
    private String environment = "DEV";

    @Column(length = 80)
    private String category = "business_api";

    @Column(length = 1000)
    private String tags;

    @Lob
    @Column(columnDefinition = "longtext")
    private String routingLabelsJson;

    @Lob
    @Column(columnDefinition = "longtext")
    private String capabilitiesJson;

    @Transient
    private List<String> routingLabels = new ArrayList<>();

    @Transient
    private List<String> capabilities = new ArrayList<>();

    @Column(nullable = false, length = 32)
    private String runtimeAction = "readonly";

    @Column(nullable = false)
    private int timeoutMs = 10000;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
