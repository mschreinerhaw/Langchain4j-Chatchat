package com.chatchat.chat.insight;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** One maintainable physical-to-semantic field mapping. */
@Getter
@Setter
@Entity
@Table(name = "runtime_semantic_insight_field",
    uniqueConstraints = @UniqueConstraint(name = "uk_semantic_field_key",
        columnNames = {"contract_id", "semantic_key"}),
    indexes = @Index(name = "idx_semantic_field_contract_order",
        columnList = "contract_id,display_order"))
public class SemanticInsightFieldEntity {
    @Id
    @Column(name = "field_id", length = 128, nullable = false)
    private String fieldId;
    @Column(name = "contract_id", length = 128, nullable = false)
    private String contractId;
    @ManyToOne
    @JoinColumn(name = "contract_id", insertable = false, updatable = false,
        foreignKey = @jakarta.persistence.ForeignKey(name = "fk_semantic_field_contract"))
    private SemanticInsightContractEntity contract;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "physical_field", length = 256, nullable = false)
    private String physicalField;
    @Column(name = "semantic_key", length = 128, nullable = false)
    private String semanticKey;
    @Column(name = "display_label", length = 512)
    private String displayLabel;
    @Column(length = 64)
    private String unit;
    @Column(length = 32)
    private String aggregation;
    @Column(nullable = false)
    private boolean sensitive;
}
