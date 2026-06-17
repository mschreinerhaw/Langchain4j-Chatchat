package com.chatchat.knowledgebase.search;

public enum ChunkType {
    DEFINITION("definition"),
    STEP("step"),
    EXAMPLE("example"),
    LOG("log"),
    TABLE("table"),
    TROUBLESHOOTING("troubleshooting"),
    POLICY("policy"),
    GENERAL("general");

    private final String value;

    ChunkType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
