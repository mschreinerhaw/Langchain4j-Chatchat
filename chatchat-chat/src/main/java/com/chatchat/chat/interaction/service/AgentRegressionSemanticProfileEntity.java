package com.chatchat.chat.interaction.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Database-owned vocabulary for deterministic Agent regression evaluation. */
@Getter
@Setter
@Entity
@Table(name = "agent_regression_semantic_profile")
public class AgentRegressionSemanticProfileEntity {

    @Id
    @Column(name = "profile_key", length = 128, nullable = false)
    private String profileKey;

    @Column(name = "connector_terms_json", length = org.hibernate.Length.LONG32, nullable = false)
    private String connectorTermsJson;

    @Column(name = "relation_rules_json", length = org.hibernate.Length.LONG32, nullable = false)
    private String relationRulesJson;
}
