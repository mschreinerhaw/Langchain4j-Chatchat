package com.chatchat.mcpserver.database;

import java.util.LinkedHashMap;
import java.util.Map;

public class DatabaseQuerySqlStep {

    private String sqlCode;
    private String sqlName;
    private String sqlDescription;
    private String sqlContent;
    private Integer executionOrder;
    private Boolean enabled = true;
    private Integer timeoutSeconds;
    private String failureStrategy = "STOP";
    private Integer maxResultRows;
    private Map<String, Object> parameters = new LinkedHashMap<>();

    public String getSqlCode() {
        return sqlCode;
    }

    public void setSqlCode(String sqlCode) {
        this.sqlCode = sqlCode;
    }

    public String getSqlName() {
        return sqlName;
    }

    public void setSqlName(String sqlName) {
        this.sqlName = sqlName;
    }

    public String getSqlDescription() {
        return sqlDescription;
    }

    public void setSqlDescription(String sqlDescription) {
        this.sqlDescription = sqlDescription;
    }

    public String getSqlContent() {
        return sqlContent;
    }

    public void setSqlContent(String sqlContent) {
        this.sqlContent = sqlContent;
    }

    public Integer getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(Integer executionOrder) {
        this.executionOrder = executionOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getFailureStrategy() {
        return failureStrategy;
    }

    public void setFailureStrategy(String failureStrategy) {
        this.failureStrategy = failureStrategy;
    }

    public Integer getMaxResultRows() {
        return maxResultRows;
    }

    public void setMaxResultRows(Integer maxResultRows) {
        this.maxResultRows = maxResultRows;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public boolean enabled() {
        return enabled == null || enabled;
    }
}
