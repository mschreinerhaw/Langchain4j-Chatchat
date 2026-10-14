package com.chatchat.common.retrieval.query;

import java.util.Map;

/** An execution diagnostic, never an empty successful recall. */
public final class QueryOptimizationException extends RuntimeException {
    private final String code;
    private final Map<String, Object> diagnostic;
    public QueryOptimizationException(String code, String message) { this(code, message, Map.of()); }
    public QueryOptimizationException(String code, String message, Map<String, Object> diagnostic) {
        super(code + ": " + message + (diagnostic.isEmpty() ? "" : " " + diagnostic)); this.code = code; this.diagnostic = Map.copyOf(diagnostic);
    }
    public String code() { return code; }
    public Map<String, Object> diagnostic() { return diagnostic; }
}
