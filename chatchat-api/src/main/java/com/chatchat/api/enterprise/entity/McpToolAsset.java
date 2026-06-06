package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "mcp_tool")
public class McpToolAsset extends EnterpriseAuditable {

    @Column(length = 128, nullable = false, unique = true)
    private String localToolName;

    @Column(length = 64, nullable = false)
    private String serviceId;

    @Column(length = 128)
    private String serviceName;

    @Column(length = 128, nullable = false)
    private String remoteToolName;

    @Column(length = 2000)
    private String description;

    @Column(length = 32, nullable = false)
    private String resourceType = "tool";

    @Column(length = 4000)
    private String inputSchemaJson;

    @Column(length = 4000)
    private String outputSchemaJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 32, nullable = false)
    private String status = "online";
}
