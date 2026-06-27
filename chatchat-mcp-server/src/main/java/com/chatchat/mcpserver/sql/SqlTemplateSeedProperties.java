package com.chatchat.mcpserver.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chatchat.mcp.sql-templates")
public class SqlTemplateSeedProperties {

    private boolean seedDefaultsEnabled = true;

    public boolean isSeedDefaultsEnabled() {
        return seedDefaultsEnabled;
    }

    public void setSeedDefaultsEnabled(boolean seedDefaultsEnabled) {
        this.seedDefaultsEnabled = seedDefaultsEnabled;
    }
}
