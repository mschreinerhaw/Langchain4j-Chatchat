package com.chatchat.mcpserver.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
@Table(name = "mcp_notification_channel_config")
public class NotificationChannelConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 32)
    private NotificationChannel channel;

    @Column(nullable = false, unique = true, length = 128)
    private String toolName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 32)
    private String runtimeAction = "confirm_required";

    @Column(nullable = false, length = 32)
    private String deliveryMode = "HTTP";

    @Column(nullable = false, length = 16)
    private String method = "POST";

    @Column(length = 2000)
    private String endpointUrl;

    @Lob
    @Column
    private String headersJson;

    @Lob
    @Column
    private String bodyTemplate;

    @Column(length = 1000)
    private String secret;

    @Column(length = 2000)
    private String defaultReceiver;

    @Column(length = 2000)
    private String ccReceiver;

    @Column(length = 300)
    private String smtpHost;

    @Column
    private Integer smtpPort;

    @Column(length = 300)
    private String smtpUsername;

    @Column(length = 1000)
    private String smtpPassword;

    @Column(length = 300)
    private String smtpFrom;

    @Column
    private Boolean smtpAuthEnabled = true;

    @Column
    private Boolean smtpStarttlsEnabled = true;

    @Column
    private Boolean smtpSslEnabled = false;

    @Column(length = 1000)
    private String smtpSslTrust;

    @Column(length = 300)
    private String smsAccount;

    @Column(length = 1000)
    private String smsToken;

    @Column(length = 1000)
    private String smsPlainPassword;

    @Column(length = 1000)
    private String smsMd5Password;

    @Column
    private Boolean smsPasswordMd5 = true;

    @Column(length = 64)
    private String smsReturnType;

    @Column(length = 128)
    private String smsExtendCode;

    @Column(nullable = false)
    private int timeoutMs = 10000;

    @Column(nullable = false)
    private int maxRetries = 1;

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

    public boolean isSmtpAuthEnabled() {
        return smtpAuthEnabled == null || smtpAuthEnabled;
    }

    public boolean isSmtpStarttlsEnabled() {
        return smtpStarttlsEnabled == null || smtpStarttlsEnabled;
    }

    public boolean isSmtpSslEnabled() {
        return smtpSslEnabled != null && smtpSslEnabled;
    }

    public boolean isSmsPasswordMd5() {
        return smsPasswordMd5 == null || smsPasswordMd5;
    }
}
