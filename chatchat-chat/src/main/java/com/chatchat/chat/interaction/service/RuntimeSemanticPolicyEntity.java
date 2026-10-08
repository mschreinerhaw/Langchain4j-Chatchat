package com.chatchat.chat.interaction.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_runtime_semantic_policy")
public class RuntimeSemanticPolicyEntity {
    @Id
    @Column(name = "policy_key", length = 64, nullable = false)
    private String policyKey;

    @Column(name = "policy_json", columnDefinition = "text", nullable = false)
    private String policyJson;
}
