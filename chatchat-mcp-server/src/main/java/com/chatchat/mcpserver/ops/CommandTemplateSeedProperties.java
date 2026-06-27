package com.chatchat.mcpserver.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chatchat.mcp.command-templates")
public class CommandTemplateSeedProperties {

    private boolean seedDefaultsEnabled = true;

    public boolean isSeedDefaultsEnabled() {
        return seedDefaultsEnabled;
    }

    public void setSeedDefaultsEnabled(boolean seedDefaultsEnabled) {
        this.seedDefaultsEnabled = seedDefaultsEnabled;
    }
}
