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

/** Generic calculation recipe plus user-facing placement policy. */
@Getter
@Setter
@Entity
@Table(name = "runtime_semantic_insight_recipe",
    uniqueConstraints = @UniqueConstraint(name = "uk_semantic_recipe_key",
        columnNames = {"contract_id", "recipe_key"}),
    indexes = @Index(name = "idx_semantic_recipe_contract_order",
        columnList = "contract_id,display_order"))
public class SemanticInsightRecipeEntity {
    @Id
    @Column(name = "recipe_id", length = 128, nullable = false)
    private String recipeId;
    @Column(name = "contract_id", length = 128, nullable = false)
    private String contractId;
    @ManyToOne
    @JoinColumn(name = "contract_id", insertable = false, updatable = false,
        foreignKey = @jakarta.persistence.ForeignKey(name = "fk_semantic_recipe_contract"))
    private SemanticInsightContractEntity contract;
    @Column(name = "recipe_key", length = 128, nullable = false)
    private String recipeKey;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(length = 64, nullable = false)
    private String operator;
    @Column(length = 512)
    private String label;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "presentation_mode", length = 32, nullable = false)
    private String presentationMode = "WHEN_RELEVANT";
    @Column(name = "conclusion_eligible", nullable = false)
    private boolean conclusionEligible = true;
    @Column(name = "presentation_priority", nullable = false)
    private int presentationPriority;
    @Column(name = "section_key", length = 128)
    private String sectionKey;
    @Column(name = "relevance_hint", length = 1000)
    private String relevanceHint;
}
