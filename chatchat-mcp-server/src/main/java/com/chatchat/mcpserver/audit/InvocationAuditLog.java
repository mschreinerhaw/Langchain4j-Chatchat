package com.chatchat.mcpserver.audit;

import java.time.Instant;

public class InvocationAuditLog {

    private String id;

    private String targetType;

    private String targetId;

    private String targetName;

    private String toolName;

    private String caller;

    private boolean success;

    private Integer statusCode;

    private Long durationMs;

    private String errorMessage;

    private String requestSummary;

    private String responseSummary;

    private Instant createdAt;

    /**
     * Returns the id.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the id.
     *
     * @param id the id value
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the target type.
     *
     * @return the target type
     */
    public String getTargetType() {
        return targetType;
    }

    /**
     * Sets the target type.
     *
     * @param targetType the target type value
     */
    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    /**
     * Returns the target id.
     *
     * @return the target id
     */
    public String getTargetId() {
        return targetId;
    }

    /**
     * Sets the target id.
     *
     * @param targetId the target id value
     */
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /**
     * Returns the target name.
     *
     * @return the target name
     */
    public String getTargetName() {
        return targetName;
    }

    /**
     * Sets the target name.
     *
     * @param targetName the target name value
     */
    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    /**
     * Returns the tool name.
     *
     * @return the tool name
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Sets the tool name.
     *
     * @param toolName the tool name value
     */
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    /**
     * Returns the caller.
     *
     * @return the caller
     */
    public String getCaller() {
        return caller;
    }

    /**
     * Sets the caller.
     *
     * @param caller the caller value
     */
    public void setCaller(String caller) {
        this.caller = caller;
    }

    /**
     * Returns whether is success.
     *
     * @return whether the condition is satisfied
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Sets the success.
     *
     * @param success the success value
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * Returns the status code.
     *
     * @return the status code
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Sets the status code.
     *
     * @param statusCode the status code value
     */
    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    /**
     * Returns the duration ms.
     *
     * @return the duration ms
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * Sets the duration ms.
     *
     * @param durationMs the duration ms value
     */
    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * Returns the error message.
     *
     * @return the error message
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the error message.
     *
     * @param errorMessage the error message value
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Returns the request summary.
     *
     * @return the request summary
     */
    public String getRequestSummary() {
        return requestSummary;
    }

    /**
     * Sets the request summary.
     *
     * @param requestSummary the request summary value
     */
    public void setRequestSummary(String requestSummary) {
        this.requestSummary = requestSummary;
    }

    /**
     * Returns the response summary.
     *
     * @return the response summary
     */
    public String getResponseSummary() {
        return responseSummary;
    }

    /**
     * Sets the response summary.
     *
     * @param responseSummary the response summary value
     */
    public void setResponseSummary(String responseSummary) {
        this.responseSummary = responseSummary;
    }

    /**
     * Returns the created at.
     *
     * @return the created at
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the created at.
     *
     * @param createdAt the created at value
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
