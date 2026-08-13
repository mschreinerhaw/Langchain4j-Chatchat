package com.chatchat.chat.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

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

    @Column(name = "rules_json", columnDefinition = "LONGTEXT", nullable = false)
    private String rulesJson;

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
