package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.insight.SemanticInsightRecipeCatalog;
import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;



import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticInsightRecipeCatalogTest {

    @Test
    void exposesMaintainableOperatorParameterDefinitions() {
        assertThat(SemanticInsightRecipeCatalog.definitionsByOperator())
            .containsKeys("SUM", "TOP_N", "RECONCILIATION", "OUTLIER_RATIO",
                "TAG_MATCH", "BUNDLE_RECONCILIATION");
        assertThat(SemanticInsightRecipeCatalog.definitionsByOperator().get("OUTLIER_RATIO")
            .parameters()).extracting(SemanticInsightRecipeCatalog.Parameter::key)
            .contains("numerator", "denominator", "entity", "threshold", "comparator");
    }

    @Test
    void rejectsMissingWrongTypeAndUnknownParametersBeforeCalculation() {
        SemanticInsightContract.Recipe recipe = new SemanticInsightContract.Recipe(
            "check-a", "OUTLIER_RATIO", "Check A",
            Map.of("numerator", "a", "denominator", "b", "entity", "id",
                "threshold", "not-a-number", "unexpected", true));

        assertThat(SemanticInsightRecipeCatalog.validate(recipe))
            .contains("threshold must be NUMBER", "unsupported parameter: unexpected");
    }

    @Test
    void unknownPresentationModeFailsClosedToSupporting() {
        SemanticInsightContract.Presentation presentation =
            new SemanticInsightContract.Presentation("unknown", true, 99, "summary", "hint");

        assertThat(presentation.mode()).isEqualTo("SUPPORTING");
        assertThat(presentation.conclusionEligible()).isFalse();
    }
}
