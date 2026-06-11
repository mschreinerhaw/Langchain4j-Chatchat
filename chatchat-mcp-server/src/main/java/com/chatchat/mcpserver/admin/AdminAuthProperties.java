package com.chatchat.mcpserver.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.admin")
public class AdminAuthProperties {

    private String username = "admin";
    private String password = "admin123";
    private long tokenTtlMinutes = 480;

    /**
     * Returns the username.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the username.
     *
     * @param username the username value
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the password.
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password.
     *
     * @param password the password value
     */
    public void setPassword(String password) {
        this.password = password;
    }

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
