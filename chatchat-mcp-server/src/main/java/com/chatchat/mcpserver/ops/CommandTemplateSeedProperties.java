package com.chatchat.mcpserver.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.command-templates")
public class CommandTemplateSeedProperties {

    private boolean seedDefaultsEnabled;

    public boolean isSeedDefaultsEnabled() {
        return seedDefaultsEnabled;
    }

    public void setSeedDefaultsEnabled(boolean seedDefaultsEnabled) {
        this.seedDefaultsEnabled = seedDefaultsEnabled;
    }
}
