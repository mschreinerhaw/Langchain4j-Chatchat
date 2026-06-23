package com.chatchat.mcpserver.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "mcp_service")
public class McpServiceRegistration {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 2000)
    private String endpoint;

    @Column(nullable = false, unique = true, length = 128)
    private String serviceToken;

    @Column(length = 64)
    private String serviceType;

    @Column(length = 128)
    private String permissionGroup;

    @Column(nullable = false, length = 32)
    private String environment = "DEV";

    @Lob
    @Column
    private String routingLabelsJson;

    @Lob
    @Column
    private String capabilitiesJson;

    @Transient
    private List<String> routingLabels = new ArrayList<>();

    @Transient
    private List<String> capabilities = new ArrayList<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column
    private Instant lastHeartbeatAt;

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
     * Returns the service token.
     *
     * @return the service token
     */
    public String getServiceToken() {
        return serviceToken;
    }

    /**
     * Sets the service token.
     *
     * @param serviceToken the service token value
     */
    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    /**
     * Returns the service type.
     *
     * @return the service type
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Sets the service type.
     *
     * @param serviceType the service type value
     */
    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    /**
     * Returns the permission group.
     *
     * @return the permission group
     */
    public String getPermissionGroup() {
        return permissionGroup;
    }

    /**
     * Sets the permission group.
     *
     * @param permissionGroup the permission group value
     */
    public void setPermissionGroup(String permissionGroup) {
        this.permissionGroup = permissionGroup;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getRoutingLabelsJson() {
        return routingLabelsJson;
    }

    public void setRoutingLabelsJson(String routingLabelsJson) {
        this.routingLabelsJson = routingLabelsJson;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson;
    }

    public List<String> getRoutingLabels() {
        return routingLabels;
    }

    public void setRoutingLabels(List<String> routingLabels) {
        this.routingLabels = routingLabels == null ? new ArrayList<>() : routingLabels;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities == null ? new ArrayList<>() : capabilities;
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
     * Returns the status.
     *
     * @return the status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the status.
     *
     * @param status the status value
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the last heartbeat at.
     *
     * @return the last heartbeat at
     */
    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    /**
     * Sets the last heartbeat at.
     *
     * @param lastHeartbeatAt the last heartbeat at value
     */
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
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
