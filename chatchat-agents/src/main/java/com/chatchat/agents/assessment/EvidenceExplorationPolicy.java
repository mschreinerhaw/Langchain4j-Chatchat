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
        if (!executionSuccessful) return concreteToolPathAvailable;
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

    private boolean nonEmpty(Object value) {
        return value instanceof Iterable<?> items && items.iterator().hasNext();
    }
}
