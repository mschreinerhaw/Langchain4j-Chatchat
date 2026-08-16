package com.chatchat.chat.skills;

import com.chatchat.chat.contract.ContractRuleNodeValue;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Immutable
@Table(
    name = "runtime_summary_contract",
    indexes = @Index(
        name = "idx_summary_contract_active",
        columnList = "contract_key, enabled, created_at"
    )
)
public class SummaryContractEntity {

    @Id
    @Column(name = "contract_id", length = 128, nullable = false)
    private String contractId;

    @Column(name = "contract_key", length = 64, nullable = false)
    private String contractKey;

    @Column(name = "contract_version", length = 64, nullable = false)
    private String contractVersion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "runtime_summary_contract_rule",
        joinColumns = @JoinColumn(name = "contract_id", nullable = false),
        uniqueConstraints = @UniqueConstraint(
            name = "uk_summary_contract_rule_path", columnNames = {"contract_id", "rule_path"}
        )
    )
    @OrderColumn(name = "storage_order")
    private List<ContractRuleNodeValue> ruleNodes = new ArrayList<>();

    @Column(name = "checksum_sha256", length = 64, nullable = false)
    private String checksumSha256;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean immutable = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
