package com.chatchat.enterprise.entity.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Database-owned argument field policy projected into the active MCP contract. */
@Getter
@Setter
@Entity
@Table(name = "mcp_argument_binding_policy")
public class McpArgumentBindingPolicy {

    @Id
    @Column(name = "policy_key", length = 64, nullable = false)
    private String policyKey;

    @Column(name = "policy_json", length = org.hibernate.Length.LONG32, nullable = false)
    private String policyJson;
}
