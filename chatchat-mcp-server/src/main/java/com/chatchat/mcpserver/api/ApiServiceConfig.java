package com.chatchat.mcpserver.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mcp_api_service_config")
public class ApiServiceConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String toolName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 2000)
    private String urlTemplate;

    @Lob
    @Column
    private String headersJson;

    @Lob
    @Column
    private String bodyTemplate;

    @Lob
    @Column
    private String inputSchemaJson;

    @Lob
    @Column
    private String governanceJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int timeoutMs = 20000;

    @Column(nullable = false)
    private boolean cacheEnabled = false;

    @Column(nullable = false)
    private int cacheTtlSeconds = 300;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Performs the pre persist operation.
     */
    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * Performs the pre update operation.
     */
    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

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
     * Returns the title.
     *
     * @return the title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the title.
     *
     * @param title the title value
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description.
     *
     * @param description the description value
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the method.
     *
     * @return the method
     */
    public String getMethod() {
        return method;
    }

    /**
     * Sets the method.
     *
     * @param method the method value
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * Returns the url template.
     *
     * @return the url template
     */
    public String getUrlTemplate() {
        return urlTemplate;
    }

    /**
     * Sets the url template.
     *
     * @param urlTemplate the url template value
     */
    public void setUrlTemplate(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    /**
     * Returns the headers json.
     *
     * @return the headers json
     */
    public String getHeadersJson() {
        return headersJson;
    }

    /**
     * Sets the headers json.
     *
     * @param headersJson the headers json value
     */
    public void setHeadersJson(String headersJson) {
        this.headersJson = headersJson;
    }

    /**
     * Returns the body template.
     *
     * @return the body template
     */
    public String getBodyTemplate() {
        return bodyTemplate;
    }

    /**
     * Sets the body template.
     *
     * @param bodyTemplate the body template value
     */
    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    /**
     * Returns the input schema json.
     *
     * @return the input schema json
     */
    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    /**
     * Sets the input schema json.
     *
     * @param inputSchemaJson the input schema json value
     */
    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    /**
     * Returns the governance json.
     *
     * @return the governance json
     */
    public String getGovernanceJson() {
        return governanceJson;
    }

    /**
     * Sets the governance json.
     *
     * @param governanceJson the governance json value
     */
    public void setGovernanceJson(String governanceJson) {
        this.governanceJson = governanceJson;
    }

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
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns whether is cache enabled.
     *
     * @return whether the condition is satisfied
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * Sets the cache enabled.
     *
     * @param cacheEnabled the cache enabled value
     */
    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * Returns the cache ttl seconds.
     *
     * @return the cache ttl seconds
     */
    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    /**
     * Sets the cache ttl seconds.
     *
     * @param cacheTtlSeconds the cache ttl seconds value
     */
    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
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
     * Returns the updated at.
     *
     * @return the updated at
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
