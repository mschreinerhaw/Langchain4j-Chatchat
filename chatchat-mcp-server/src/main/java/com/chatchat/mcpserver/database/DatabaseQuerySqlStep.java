package com.chatchat.mcpserver.database;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatabaseQuerySqlStep {

    private String sqlCode;
    private String sqlName;
    private String sqlDescription;
    private String sqlContent;
    private Integer executionOrder;
    private List<String> dependencies = new ArrayList<>();
    private Boolean workflowEnabled = false;
    private Boolean enabled = true;
    private Integer timeoutSeconds;
    private String failureStrategy = "STOP";
    private String emptyResultStrategy = "CONTINUE";
    private Integer maxResultRows;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    private List<DatabaseQueryParameterMapping> parameterMappings = new ArrayList<>();
    private DatabaseQueryResultSemantic resultSemantic = new DatabaseQueryResultSemantic();
    private Boolean returnToModel = true;

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

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    }
    public Boolean getWorkflowEnabled() { return workflowEnabled; }
    public void setWorkflowEnabled(Boolean workflowEnabled) { this.workflowEnabled = workflowEnabled; }

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

    public String getEmptyResultStrategy() { return emptyResultStrategy; }
    public void setEmptyResultStrategy(String emptyResultStrategy) { this.emptyResultStrategy = emptyResultStrategy; }

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

    public List<DatabaseQueryParameterMapping> getParameterMappings() { return parameterMappings; }
    public void setParameterMappings(List<DatabaseQueryParameterMapping> parameterMappings) {
        this.parameterMappings = parameterMappings == null ? new ArrayList<>() : new ArrayList<>(parameterMappings);
    }

    public DatabaseQueryResultSemantic getResultSemantic() { return resultSemantic; }
    public void setResultSemantic(DatabaseQueryResultSemantic resultSemantic) {
        this.resultSemantic = resultSemantic == null ? new DatabaseQueryResultSemantic() : resultSemantic;
    }

    public Boolean getReturnToModel() { return returnToModel; }
    public void setReturnToModel(Boolean returnToModel) { this.returnToModel = returnToModel; }

    public boolean enabled() {
        return enabled == null || enabled;
    }
}
