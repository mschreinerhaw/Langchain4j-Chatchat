package com.chatchat.mcpserver.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mcp_metadata_term_mapping", uniqueConstraints = {
    @UniqueConstraint(name = "uk_metadata_scenario_term", columnNames = {"scenario_id", "normalized_term"})
}, indexes = {
    @Index(name = "idx_metadata_term_scenario", columnList = "scenario_id")
})
public class MetadataTermMapping {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "scenario_id", nullable = false, length = 64)
    private String scenarioId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", insertable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_metadata_term_scenario"))
    private MetadataScenario scenario;

    @Column(nullable = false, length = 128)
    private String term;

    @Column(nullable = false, length = 128)
    private String normalizedTerm;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal weight = BigDecimal.ONE;

    @Column(nullable = false, length = 32)
    private String matchType = "CONTAINS";

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        normalize();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        normalize();
        updatedAt = Instant.now();
    }

    private void normalize() {
        normalizedTerm = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        matchType = matchType == null || matchType.isBlank()
            ? "CONTAINS"
            : matchType.trim().toUpperCase(Locale.ROOT);
    }
}
