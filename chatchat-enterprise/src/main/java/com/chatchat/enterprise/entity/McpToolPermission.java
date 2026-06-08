package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_tool_permission")
public class McpToolPermission extends EnterpriseAuditable {

    @Column(length = 64)
    private String tenantId;

    @Column(length = 32, nullable = false)
    private String targetType;

    @Column(length = 64, nullable = false)
    private String targetId;

    @Column(length = 64)
    private String toolId;

    @Column(length = 128, nullable = false)
    private String localToolName;

    @Column(length = 16, nullable = false)
    private String effect = "allow";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column
    private Instant expiresAt;

    @Column(length = 1000)
    private String remark;
}
