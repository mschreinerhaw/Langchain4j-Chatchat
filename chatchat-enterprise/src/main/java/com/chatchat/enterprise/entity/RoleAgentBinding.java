package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "role_agent_binding",
    indexes = {
        @Index(name = "idx_role_agent_role", columnList = "role_id"),
        @Index(name = "idx_role_agent_agent", columnList = "tenant_id, agent_id")
    }
)
public class RoleAgentBinding extends EnterpriseAuditable {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "role_id", length = 64, nullable = false)
    private String roleId;

    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;
}
