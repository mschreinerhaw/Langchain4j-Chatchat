package com.chatchat.enterprise.entity.identity;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Getter;

@MappedSuperclass
public abstract class ScheduledIdentity extends EnterpriseAuditable {
    @Column(name = "effective_at")
    @Getter
    private Instant effectiveAt;
    @Column(name = "expires_at")
    @Getter
    private Instant expiresAt;
    @Transient
    private boolean effectiveAtSpecified;
    @Transient
    private boolean expiresAtSpecified;

    public abstract String getStatus();

    public void setEffectiveAt(Instant value) {
        effectiveAt = value;
        effectiveAtSpecified = true;
    }

    public void setExpiresAt(Instant value) {
        expiresAt = value;
        expiresAtSpecified = true;
    }

    public boolean activeAt(Instant now) {
        return "enabled".equalsIgnoreCase(getStatus())
            && (effectiveAt == null || !effectiveAt.isAfter(now))
            && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void applySchedule(ScheduledIdentity input, boolean protectedIdentity) {
        Instant from = input.effectiveAtSpecified ? input.effectiveAt : effectiveAt;
        Instant until = input.expiresAtSpecified ? input.expiresAt : expiresAt;
        if (from != null && until != null && !from.isBefore(until))
            throw new IllegalArgumentException("授权生效时间必须早于到期时间");
        if (protectedIdentity && (from != null || until != null))
            throw new IllegalArgumentException("保留用于系统续期和权限恢复的管理身份须永久有效");
        effectiveAt = from;
        expiresAt = until;
    }
}
