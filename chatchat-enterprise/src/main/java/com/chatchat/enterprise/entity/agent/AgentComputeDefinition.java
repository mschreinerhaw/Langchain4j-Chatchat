package com.chatchat.enterprise.entity.agent;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_compute_definition", indexes = {
    @Index(name = "idx_agent_compute_id", columnList = "agent_id", unique = true),
    @Index(name = "idx_agent_compute_enabled", columnList = "enabled")
})
public class AgentComputeDefinition extends EnterpriseAuditable {
    @Column(name = "agent_id", length = 160, nullable = false, unique = true)
    private String agentId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "descriptor_json", length = org.hibernate.Length.LONG32, nullable = false)
    private String descriptorJson;
}
