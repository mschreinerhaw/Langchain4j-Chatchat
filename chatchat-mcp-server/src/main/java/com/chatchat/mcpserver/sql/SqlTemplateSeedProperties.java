package com.chatchat.mcpserver.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.sql-templates")
public class SqlTemplateSeedProperties {

    private boolean seedDefaultsEnabled;

    public boolean isSeedDefaultsEnabled() {
        return seedDefaultsEnabled;
    }

    public void setSeedDefaultsEnabled(boolean seedDefaultsEnabled) {
        this.seedDefaultsEnabled = seedDefaultsEnabled;
    }
}
