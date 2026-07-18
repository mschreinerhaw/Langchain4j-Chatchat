package com.chatchat.runtime.news.compliance;

import java.time.Instant;

/** Structured result returned to the administration UI for a robots.txt preflight check. */
public record RobotsComplianceReport(
    boolean allowed,
    String status,
    String targetUrl,
    String robotsUrl,
    Integer httpStatus,
    String matchedRule,
    String message,
    int checkedUrlCount,
    Instant checkedAt,
    boolean overridden,
    String overrideReason,
    Instant overrideUntil
) {
    public RobotsComplianceReport(boolean allowed, String status, String targetUrl, String robotsUrl,
                                  Integer httpStatus, String matchedRule, String message,
                                  int checkedUrlCount, Instant checkedAt) {
        this(allowed, status, targetUrl, robotsUrl, httpStatus, matchedRule, message,
            checkedUrlCount, checkedAt, false, null, null);
    }
}
