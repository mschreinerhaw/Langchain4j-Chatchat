package com.chatchat.mcpserver.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.authorization")
public class McpAuthorizationProperties {

    private boolean enabled = false;
    private String apiBaseUrl = "http://localhost:8080";
    private String snapshotPath = "/api/v1/enterprise/mcp-auth/snapshot";
    private long refreshIntervalMs = 60000L;
    private long staleTtlSeconds = 3600L;
    private boolean failOpen = true;
    private boolean requireTenantContext = true;
    private Auth auth = new Auth();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public long getStaleTtlSeconds() {
        return staleTtlSeconds;
    }

    public void setStaleTtlSeconds(long staleTtlSeconds) {
        this.staleTtlSeconds = staleTtlSeconds;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public boolean isRequireTenantContext() {
        return requireTenantContext;
    }

    public void setRequireTenantContext(boolean requireTenantContext) {
        this.requireTenantContext = requireTenantContext;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth == null ? new Auth() : auth;
    }

    public static class Auth {
        private boolean enabled = true;
        private String loginPath = "/api/v1/enterprise/auth/login";
        private String username = "admin";
        private String password = "123456";
        private String bearerToken = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
        }
    }
}
