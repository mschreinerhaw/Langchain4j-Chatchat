package com.chatchat.agents.assessment;

import java.util.Map;

/** Decides whether a persisted evidence gap has a bounded retrieval path. */
public final class EvidenceExplorationPolicy {

    public boolean available(Map<String, Object> snapshot,
                             boolean executionSuccessful,
                             boolean toolsAvailable,
                             boolean budgetAvailable,
                             boolean concreteToolPathAvailable) {
        if (!budgetAvailable || !toolsAvailable) return false;
        if (!executionSuccessful) return !containsDeclaredAuthoritativeEmptyResult(snapshot);
        if (snapshot == null) return false;
        if (concreteToolPathAvailable || nonEmpty(snapshot.get("nextActions"))) return true;
        Object evidence = snapshot.get("toolEvidence");
        if (evidence instanceof Iterable<?> items) {
            for (Object raw : items) {
                if (raw instanceof Map<?, ?> item && Boolean.TRUE.equals(item.get("shouldExpandQuery"))) return true;
            }
        }
        return false;
    }

    private boolean containsDeclaredAuthoritativeEmptyResult(Map<String, Object> snapshot) {
        return snapshot != null && containsDeclaredAuthoritativeEmptyResult(snapshot.get("toolEvidence"), 0);
    }

    private boolean containsDeclaredAuthoritativeEmptyResult(Object value, int depth) {
        if (value == null || depth > 8) return false;
        if (value instanceof Map<?, ?> map) {
            Object returnedCount = map.get("returnedCount");
            Object success = map.get("success");
            if (Boolean.TRUE.equals(success) && returnedCount instanceof Number number
                && number.longValue() == 0L) return true;
            for (Object nested : map.values()) {
                if (containsDeclaredAuthoritativeEmptyResult(nested, depth + 1)) return true;
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (containsDeclaredAuthoritativeEmptyResult(item, depth + 1)) return true;
            }
        }
        return false;
    }

    private boolean nonEmpty(Object value) {
        return value instanceof Iterable<?> items && items.iterator().hasNext();
    }
}
