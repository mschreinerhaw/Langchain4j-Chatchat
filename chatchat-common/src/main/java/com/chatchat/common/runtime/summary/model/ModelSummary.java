package com.chatchat.common.runtime.summary.model;

import java.util.List;
import java.util.Map;

/**
 * Framework-neutral summary product exchanged between Runtime OS summary workers and callers.
 * Implementations may add domain fields, but must preserve content, outcome and lineage.
 */
public interface ModelSummary {

    String resultId();

    String content();

    String outcome();

    List<String> inputSummaryResultIds();

    Map<String, Object> evidence();

    Map<String, Object> toMap();
}
