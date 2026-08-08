package com.chatchat.common.audit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared query policy for persisted audit information.
 *
 * <p>The policy limits only the default application view. It does not delete,
 * expire, or otherwise modify historical audit records.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.audit")
public class AuditQueryProperties {

    private static final int MIN_WINDOW_DAYS = 1;

    /**
     * Number of recent days shown when a query does not provide its own lower bound.
     */
    private int defaultQueryWindowDays = 7;

    public int effectiveDefaultQueryWindowDays() {
        return Math.max(MIN_WINDOW_DAYS, defaultQueryWindowDays);
    }

    public Instant defaultQueryFrom(Instant referenceTime) {
        Instant reference = referenceTime == null ? Instant.now() : referenceTime;
        return reference.minus(Duration.ofDays(effectiveDefaultQueryWindowDays()));
    }
}
