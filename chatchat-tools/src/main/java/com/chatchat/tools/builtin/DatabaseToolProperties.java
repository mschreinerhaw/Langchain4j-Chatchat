package com.chatchat.tools.builtin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.tools.database-query")
public class DatabaseToolProperties {

    private boolean enabled = true;

    private int defaultMaxRows = 50;

    private int minRows = 1;

    private int maxRows = 500;

    private int queryTimeoutSeconds = 15;

    private String driverLibPath = "./lib";

    private List<String> allowedPrefixes = List.of("select", "with", "show", "describe", "desc", "explain");

    private List<String> blockedKeywords = List.of(
        "insert", "update", "delete", "merge", "drop", "alter", "create", "truncate",
        "grant", "revoke", "call", "execute", "replace", "commit", "rollback"
    );
}
