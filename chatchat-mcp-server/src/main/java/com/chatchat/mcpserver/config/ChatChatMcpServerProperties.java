package com.chatchat.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "chatchat.mcp.server")
public class ChatChatMcpServerProperties {

    private String name = "chatchat-langchain4j-mcp-server";
    private String version = "1.0.0-SNAPSHOT";
    private String endpoint = "/mcp";
    private boolean exposeAgentCompatibleOnly = true;
    private Set<String> excludedToolNames = new LinkedHashSet<>();
    private String instructions = "ChatChat standalone MCP server exposing LangChain4j-compatible tools.";
    private ConcurrencyProperties concurrency = new ConcurrencyProperties();
    private DocumentSearchProperties documentSearch = new DocumentSearchProperties();
    private OutputProperties output = new OutputProperties();

    /**
     * Returns the name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name.
     *
     * @param name the name value
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the version.
     *
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the version.
     *
     * @param version the version value
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the endpoint.
     *
     * @return the endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the endpoint.
     *
     * @param endpoint the endpoint value
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Returns whether is expose agent compatible only.
     *
     * @return whether the condition is satisfied
     */
    public boolean isExposeAgentCompatibleOnly() {
        return exposeAgentCompatibleOnly;
    }

    /**
     * Sets the expose agent compatible only.
     *
     * @param exposeAgentCompatibleOnly the expose agent compatible only value
     */
    public void setExposeAgentCompatibleOnly(boolean exposeAgentCompatibleOnly) {
        this.exposeAgentCompatibleOnly = exposeAgentCompatibleOnly;
    }

    /**
     * Returns the excluded tool names.
     *
     * @return the excluded tool names
     */
    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    /**
     * Sets the excluded tool names.
     *
     * @param excludedToolNames the excluded tool names value
     */
    public void setExcludedToolNames(Set<String> excludedToolNames) {
        this.excludedToolNames = excludedToolNames == null ? new LinkedHashSet<>() : excludedToolNames;
    }

    /**
     * Returns the instructions.
     *
     * @return the instructions
     */
    public String getInstructions() {
        return instructions;
    }

    /**
     * Sets the instructions.
     *
     * @param instructions the instructions value
     */
    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    /**
     * Returns the concurrency.
     *
     * @return the concurrency
     */
    public ConcurrencyProperties getConcurrency() {
        return concurrency;
    }

    /**
     * Sets the concurrency.
     *
     * @param concurrency the concurrency value
     */
    public void setConcurrency(ConcurrencyProperties concurrency) {
        this.concurrency = concurrency == null ? new ConcurrencyProperties() : concurrency;
    }

    /**
     * Returns the document search.
     *
     * @return the document search
     */
    public DocumentSearchProperties getDocumentSearch() {
        return documentSearch;
    }

    /**
     * Sets the document search.
     *
     * @param documentSearch the document search value
     */
    public void setDocumentSearch(DocumentSearchProperties documentSearch) {
        this.documentSearch = documentSearch == null ? new DocumentSearchProperties() : documentSearch;
    }

    public OutputProperties getOutput() {
        return output;
    }

    public void setOutput(OutputProperties output) {
        this.output = output == null ? new OutputProperties() : output;
    }

    public static class OutputProperties {

        /**
         * Maximum characters for sql_metadata_search text summary. -1 means unlimited.
         */
        private int sqlMetadataSearchSummaryMaxChars = 6_000;

        /**
         * Maximum column rows shown in sql_metadata_search text summary. -1 means unlimited.
         */
        private int sqlMetadataSearchSummaryMaxColumns = 30;

        public int getSqlMetadataSearchSummaryMaxChars() {
            return sqlMetadataSearchSummaryMaxChars;
        }

        public void setSqlMetadataSearchSummaryMaxChars(int sqlMetadataSearchSummaryMaxChars) {
            this.sqlMetadataSearchSummaryMaxChars = sqlMetadataSearchSummaryMaxChars;
        }

        public int getSqlMetadataSearchSummaryMaxColumns() {
            return sqlMetadataSearchSummaryMaxColumns;
        }

        public void setSqlMetadataSearchSummaryMaxColumns(int sqlMetadataSearchSummaryMaxColumns) {
            this.sqlMetadataSearchSummaryMaxColumns = sqlMetadataSearchSummaryMaxColumns;
        }
    }

    public static class DocumentSearchProperties {

        private String apiBaseUrl = "http://localhost:8080";
        private String endpointPath = "/api/v1/search/document-search";
        private int timeoutMs = 60000;
        private AuthProperties auth = new AuthProperties();

        /**
         * Returns the api base url.
         *
         * @return the api base url
         */
        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        /**
         * Sets the api base url.
         *
         * @param apiBaseUrl the api base url value
         */
        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        /**
         * Returns the endpoint path.
         *
         * @return the endpoint path
         */
        public String getEndpointPath() {
            return endpointPath;
        }

        /**
         * Sets the endpoint path.
         *
         * @param endpointPath the endpoint path value
         */
        public void setEndpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
        }

        /**
         * Returns the timeout ms.
         *
         * @return the timeout ms
         */
        public int getTimeoutMs() {
            return timeoutMs;
        }

        /**
         * Sets the timeout ms.
         *
         * @param timeoutMs the timeout ms value
         */
        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs <= 0 ? 60000 : timeoutMs;
        }

        /**
         * Returns the auth.
         *
         * @return the auth
         */
        public AuthProperties getAuth() {
            return auth;
        }

        /**
         * Sets the auth.
         *
         * @param auth the auth value
         */
        public void setAuth(AuthProperties auth) {
            this.auth = auth == null ? new AuthProperties() : auth;
        }

        public static class AuthProperties {

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

    public static class ConcurrencyProperties {

        private boolean enabled = true;
        private LimitProperties global = new LimitProperties(64, 128, 5, 30, "global");
        private LimitProperties defaults = new LimitProperties(8, 64, 5, 30, "tool");
        private LimitProperties user = new LimitProperties(8, 64, 5, 30, "user");
        private LimitProperties agent = new LimitProperties(4, 32, 5, 30, "agent");
        private Map<String, LimitProperties> runtimeLevels = defaultRuntimeLevels();
        private Map<String, LimitProperties> tools = new LinkedHashMap<>();

        /**
         * Returns whether is enabled.
         *
         * @return whether the condition is satisfied
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets the enabled.
         *
         * @param enabled the enabled value
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the global.
         *
         * @return the global
         */
        public LimitProperties getGlobal() {
            return global;
        }

        /**
         * Sets the global.
         *
         * @param global the global value
         */
        public void setGlobal(LimitProperties global) {
            this.global = global == null ? new LimitProperties(64, 128, 5, 30, "global") : global;
        }

        /**
         * Returns the defaults.
         *
         * @return the defaults
         */
        public LimitProperties getDefaults() {
            return defaults;
        }

        /**
         * Sets the defaults.
         *
         * @param defaults the defaults value
         */
        public void setDefaults(LimitProperties defaults) {
            this.defaults = defaults == null ? new LimitProperties(8, 64, 5, 30, "tool") : defaults;
        }

        /**
         * Returns the user.
         *
         * @return the user
         */
        public LimitProperties getUser() {
            return user;
        }

        /**
         * Sets the user.
         *
         * @param user the user value
         */
        public void setUser(LimitProperties user) {
            this.user = user == null ? new LimitProperties(8, 64, 5, 30, "user") : user;
        }

        /**
         * Returns the agent.
         *
         * @return the agent
         */
        public LimitProperties getAgent() {
            return agent;
        }

        /**
         * Sets the agent.
         *
         * @param agent the agent value
         */
        public void setAgent(LimitProperties agent) {
            this.agent = agent == null ? new LimitProperties(4, 32, 5, 30, "agent") : agent;
        }

        /**
         * Returns the runtime levels.
         *
         * @return the runtime levels
         */
        public Map<String, LimitProperties> getRuntimeLevels() {
            return runtimeLevels;
        }

        /**
         * Sets the runtime levels.
         *
         * @param runtimeLevels the runtime levels value
         */
        public void setRuntimeLevels(Map<String, LimitProperties> runtimeLevels) {
            this.runtimeLevels = runtimeLevels == null ? new LinkedHashMap<>() : runtimeLevels;
        }

        /**
         * Returns the tools.
         *
         * @return the tools
         */
        public Map<String, LimitProperties> getTools() {
            return tools;
        }

        /**
         * Sets the tools.
         *
         * @param tools the tools value
         */
        public void setTools(Map<String, LimitProperties> tools) {
            this.tools = tools == null ? new LinkedHashMap<>() : tools;
        }

        private static Map<String, LimitProperties> defaultRuntimeLevels() {
            Map<String, LimitProperties> values = new LinkedHashMap<>();
            values.put("ssh", new LimitProperties(2, 32, 10, 30, "ssh"));
            values.put("sql", new LimitProperties(5, 64, 10, 30, "sql"));
            values.put("sql_script", new LimitProperties(2, 32, 10, 180, "sql_script"));
            values.put("http", new LimitProperties(30, 256, 5, 30, "http"));
            values.put("notification", new LimitProperties(10, 128, 5, 30, "notification"));
            return values;
        }
    }

    public static class LimitProperties {

        private int maxConcurrency = 8;
        private int queueSize = 64;
        private long queueTimeoutSeconds = 5;
        private long timeoutSeconds = 30;
        private String runtimeLevel = "tool";
        private int maxOutputChars = 200_000;
        private int retryAttempts = 0;
        private int failureThreshold = 5;
        private long circuitOpenSeconds = 30;

        public LimitProperties() {
        }

        public LimitProperties(int maxConcurrency, int queueSize, long queueTimeoutSeconds,
                               long timeoutSeconds, String runtimeLevel) {
            this.maxConcurrency = maxConcurrency;
            this.queueSize = queueSize;
            this.queueTimeoutSeconds = queueTimeoutSeconds;
            this.timeoutSeconds = timeoutSeconds;
            this.runtimeLevel = runtimeLevel;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        public long getQueueTimeoutSeconds() {
            return queueTimeoutSeconds;
        }

        public void setQueueTimeoutSeconds(long queueTimeoutSeconds) {
            this.queueTimeoutSeconds = queueTimeoutSeconds;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getRuntimeLevel() {
            return runtimeLevel;
        }

        public void setRuntimeLevel(String runtimeLevel) {
            this.runtimeLevel = runtimeLevel;
        }

        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getCircuitOpenSeconds() {
            return circuitOpenSeconds;
        }

        public void setCircuitOpenSeconds(long circuitOpenSeconds) {
            this.circuitOpenSeconds = circuitOpenSeconds;
        }
    }
}
