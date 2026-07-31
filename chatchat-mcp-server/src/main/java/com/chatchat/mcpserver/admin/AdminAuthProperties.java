package com.chatchat.mcpserver.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.admin")
public class AdminAuthProperties {

    private long tokenTtlMinutes = 480;

    /**
     * Returns the token ttl minutes.
     *
     * @return the token ttl minutes
     */
    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    /**
     * Sets the token ttl minutes.
     *
     * @param tokenTtlMinutes the token ttl minutes value
     */
    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }
}
