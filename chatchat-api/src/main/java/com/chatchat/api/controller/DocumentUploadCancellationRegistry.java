package com.chatchat.api.controller;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DocumentUploadCancellationRegistry {

    private final Map<String, Thread> activeUploads = new ConcurrentHashMap<>();

    public void register(String requestId) {
        String normalized = normalize(requestId);
        if (normalized == null) {
            return;
        }
        activeUploads.put(normalized, Thread.currentThread());
    }

    public void complete(String requestId) {
        String normalized = normalize(requestId);
        if (normalized != null) {
            activeUploads.remove(normalized, Thread.currentThread());
        }
    }

    public boolean cancel(String requestId) {
        String normalized = normalize(requestId);
        Thread thread = normalized == null ? null : activeUploads.get(normalized);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    private String normalize(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String normalized = requestId.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid upload request id");
        }
        return normalized;
    }
}
