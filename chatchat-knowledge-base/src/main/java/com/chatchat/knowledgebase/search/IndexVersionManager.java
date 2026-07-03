package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class IndexVersionManager {

    public boolean retrievable(SearchResult result) {
        if (result == null) {
            return false;
        }
        if (!result.latestVersion() || result.deletedAt() != null || hasText(result.errorMessage())) {
            return false;
        }
        return activeLifecycle(result.lifecycleStatus());
    }

    public boolean retrievable(SearchDocument document) {
        if (document == null) {
            return false;
        }
        if (Boolean.FALSE.equals(document.getLatestVersion())
            || document.getDeletedAt() != null
            || hasText(document.getErrorMessage())) {
            return false;
        }
        return activeLifecycle(document.getLifecycleStatus());
    }

    private boolean activeLifecycle(String status) {
        if (!hasText(status)) {
            return true;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("deleted")
            && !normalized.equals("disabled")
            && !normalized.equals("failed")
            && !normalized.equals("error");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
