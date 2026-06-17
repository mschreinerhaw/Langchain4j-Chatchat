package com.chatchat.knowledgebase.search;

public final class DocumentLifecycleStatus {

    public static final String UPLOADED = "UPLOADED";
    public static final String PARSING = "PARSING";
    public static final String INDEXED = "INDEXED";
    public static final String FAILED = "FAILED";
    public static final String DELETED = "DELETED";

    private DocumentLifecycleStatus() {
    }
}
