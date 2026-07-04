package com.chatchat.mcpserver.database;

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
@Table(name = "mcp_database_query_config")
public class DatabaseQueryConfig {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 128)
    private String toolName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 64)
    private String datasourceId;

    @Column(length = 1000)
    private String description;

    @Column(length = 128)
    private String businessGroup = "default";

    @Column(length = 200)
    private String businessGroupName;

    @Column(length = 1000)
    private String businessGroupDescription;

    @Lob
    @Column(nullable = false)
    private String sqlTemplate;

    @Lob
    @Column
    private String inputSchemaJson;

    @Lob
    @Column
    private String governanceJson;

    @Lob
    @Column
    private String routingLabelsJson;

    @Lob
    @Column
    private String capabilitiesJson;

    @Column(length = 128)
    private String templateIntent;

    @Column(length = 64)
    private String databaseType = "generic";

    @Lob
    @Column
    private String tagsJson;

    @Column(length = 32)
    private String riskLevel = "read_only";

    @Column(length = 128)
    private String owner = "admin";

    @Column(nullable = false)
    private double rating = 0.0;

    @Column(nullable = false)
    private long usageCount = 0L;

    @Transient
    private List<String> routingLabels = new ArrayList<>();

    @Transient
    private List<String> capabilities = new ArrayList<>();

    @Column(nullable = false)
    private int maxRows = 50;

    @Column
    private Integer timeoutSeconds = 30;

    @Column(length = 2000)
    private String jdbcUrl;

    @Column(length = 500)
    private String driverClass;

    @Column(length = 500)
    private String username;

    @Column(length = 1000)
    private String password;

    @Column(nullable = false)
    private boolean reloadDrivers = false;

    @Column(nullable = false)
    private boolean enabled = true;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBusinessGroup() { return businessGroup; }
    public void setBusinessGroup(String businessGroup) { this.businessGroup = businessGroup; }
    public String getBusinessGroupName() { return businessGroupName; }
    public void setBusinessGroupName(String businessGroupName) { this.businessGroupName = businessGroupName; }
    public String getBusinessGroupDescription() { return businessGroupDescription; }
    public void setBusinessGroupDescription(String businessGroupDescription) { this.businessGroupDescription = businessGroupDescription; }
    public String getSqlTemplate() { return sqlTemplate; }
    public void setSqlTemplate(String sqlTemplate) { this.sqlTemplate = sqlTemplate; }
    public String getInputSchemaJson() { return inputSchemaJson; }
    public void setInputSchemaJson(String inputSchemaJson) { this.inputSchemaJson = inputSchemaJson; }
    public String getGovernanceJson() { return governanceJson; }
    public void setGovernanceJson(String governanceJson) { this.governanceJson = governanceJson; }
    public String getRoutingLabelsJson() { return routingLabelsJson; }
    public void setRoutingLabelsJson(String routingLabelsJson) { this.routingLabelsJson = routingLabelsJson; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getTemplateIntent() { return templateIntent; }
    public void setTemplateIntent(String templateIntent) { this.templateIntent = templateIntent; }
    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }
    public long getUsageCount() { return usageCount; }
    public void setUsageCount(long usageCount) { this.usageCount = usageCount; }
    public List<String> getRoutingLabels() { return routingLabels; }
    public void setRoutingLabels(List<String> routingLabels) { this.routingLabels = routingLabels == null ? new ArrayList<>() : routingLabels; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities == null ? new ArrayList<>() : capabilities; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getTimeoutSeconds() { return timeoutSeconds == null || timeoutSeconds <= 0 ? 30 : timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getDriverClass() { return driverClass; }
    public void setDriverClass(String driverClass) { this.driverClass = driverClass; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isReloadDrivers() { return reloadDrivers; }
    public void setReloadDrivers(boolean reloadDrivers) { this.reloadDrivers = reloadDrivers; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
