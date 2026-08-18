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

import java.math.BigDecimal;

/** Typed recipe parameter; exactly one value column is populated according to value_type. */
@Getter
@Setter
@Entity
@Table(name = "runtime_semantic_insight_recipe_parameter",
    uniqueConstraints = @UniqueConstraint(name = "uk_semantic_recipe_parameter",
        columnNames = {"recipe_id", "parameter_key"}),
    indexes = @Index(name = "idx_semantic_parameter_recipe_order",
        columnList = "recipe_id,display_order"))
public class SemanticInsightRecipeParameterEntity {
    @Id
    @Column(name = "parameter_id", length = 128, nullable = false)
    private String parameterId;
    @Column(name = "recipe_id", length = 128, nullable = false)
    private String recipeId;
    @ManyToOne
    @JoinColumn(name = "recipe_id", insertable = false, updatable = false,
        foreignKey = @jakarta.persistence.ForeignKey(name = "fk_semantic_parameter_recipe"))
    private SemanticInsightRecipeEntity recipe;
    @Column(name = "parameter_key", length = 128, nullable = false)
    private String parameterKey;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "value_type", length = 16, nullable = false)
    private String valueType;
    @Column(name = "string_value", length = 2000)
    private String stringValue;
    @Column(name = "decimal_value", precision = 38, scale = 10)
    private BigDecimal decimalValue;
    @Column(name = "integer_value")
    private Long integerValue;
    @Column(name = "boolean_value")
    private Boolean booleanValue;

    public Object typedValue() {
        if (valueType == null) return null;
        return switch (valueType.trim().toUpperCase()) {
            case "DECIMAL" -> decimalValue;
            case "INTEGER" -> integerValue;
            case "BOOLEAN" -> booleanValue;
            case "STRING" -> stringValue;
            default -> throw new IllegalArgumentException("Unsupported parameter value type: " + valueType);
        };
    }
}
