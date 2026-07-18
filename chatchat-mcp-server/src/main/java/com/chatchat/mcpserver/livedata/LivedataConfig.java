package com.chatchat.mcpserver.livedata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_livedata_config")
public class LivedataConfig {

    public static final String SINGLETON_ID = "default";

    @Id
    @Column(length = 64)
    private String id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(length = 64)
    private String datasourceId;

    @Column(length = 64)
    private String gatewayId;

    @Column(length = 200)
    private String tableName = "ld_dataservice_api";

    @Column(length = 1000)
    private String serviceBaseUrl;

    @Column(length = 500)
    private String servicePathTemplate = "/service/{serviceName}/call";

    @Column(nullable = false)
    private boolean loginEnabled = true;

    @Column(length = 500)
    private String loginPath = "/login";

    @Column(length = 200)
    private String loginId;

    @Column(length = 500)
    private String loginPwd;

    @Column(nullable = false)
    private int loginTimeoutMs = 10000;

    @Column(nullable = false)
    private int sessionTtlSeconds = 1800;

    @Column(length = 500)
    private String amsToken;

    @Column(length = 100)
    private String defaultNamespace = "livedata";

    @Column(length = 80)
    private String toolNamePrefix = "livedata_";

    @Column(nullable = false)
    private int publishedState = 0;

    @Column(nullable = false)
    private int maxApis = 1000;

    @Column(nullable = false)
    private int timeoutMs = 20000;

    @Column(nullable = false)
    private boolean cacheEnabled = false;

    @Column(nullable = false)
    private int cacheTtlSeconds = 300;

    @Column(nullable = false)
    private boolean overwriteExisting = false;

    @Column(nullable = false)
    private boolean includeUnpublishedAsDisabled = false;

    @Column(nullable = false)
    private boolean exposeAmsTokenParameter = false;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = SINGLETON_ID;
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
