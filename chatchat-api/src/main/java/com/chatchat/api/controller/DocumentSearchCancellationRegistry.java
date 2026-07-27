package com.chatchat.api.controller;

import com.chatchat.knowledgebase.search.SearchPermissionContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks synchronous document searches by tenant and client request id.
 * Identical request ids in different tenants are deliberately independent.
 */
@Component
public class DocumentSearchCancellationRegistry {

    private final Map<SearchKey, Thread> activeSearches = new ConcurrentHashMap<>();

    public void register(String tenantId, String requestId) {
        SearchKey key = key(tenantId, requestId);
        if (key == null) {
            return;
        }
        Thread existing = activeSearches.putIfAbsent(key, Thread.currentThread());
        if (existing != null) {
            throw new IllegalArgumentException("search request is already running for this tenant");
        }
    }

    public void complete(String tenantId, String requestId) {
        SearchKey key = key(tenantId, requestId);
        if (key != null) {
            activeSearches.remove(key, Thread.currentThread());
        }
    }

    public boolean cancel(String tenantId, String requestId) {
        SearchKey key = key(tenantId, requestId);
        Thread thread = key == null ? null : activeSearches.get(key);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    int activeCount(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        return (int) activeSearches.keySet().stream()
            .filter(key -> key.tenantId().equals(normalizedTenant))
            .count();
    }

    private SearchKey key(String tenantId, String requestId) {
        String normalizedRequestId = normalizeRequestId(requestId);
        return normalizedRequestId == null
            ? null
            : new SearchKey(normalizeTenant(tenantId), normalizedRequestId);
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank()
            ? SearchPermissionContext.DEFAULT_TENANT
            : tenantId.trim();
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String normalized = requestId.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid search request id");
        }
        return normalized;
    }

    private record SearchKey(String tenantId, String requestId) {
    }
}
