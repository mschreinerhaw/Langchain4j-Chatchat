package com.chatchat.mcpserver.database.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseQueryParameterSyntaxTest {
    @Test void convertsCompleteTemplateValuesIncludingRepeatedAndQuotedParameters() {
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters(
            "SELECT {{id}}, {{ id }}, '{{name}}', \"{{category}}\" WHERE x = :legacy"))
            .isEqualTo("SELECT :id, :id, :name, :category WHERE x = :legacy");
    }

    @Test void preservesLiteralsCommentsQuotedIdentifiersCastsAndDollarQuotedBodies() {
        String sql = "SELECT '{{not_embedded}} text', ':literal', `{{identifier}}`, [{{identifier}}], "
            + "$$ {{body}} $$, $fn$ {{body}} $fn$, value::text, {{actual}} "
            + "-- {{comment}}\n/* {{block}} /* {{nested}} */ */ # {{line}}\n";
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters(sql))
            .isEqualTo(sql.replace("{{actual}}", ":actual"));
    }

    @Test void retainsExistingNamedSyntaxAndDynamicDateExpressions() {
        String sql = "SELECT :legacy, ${trade_date-1}, {{trade_date-1}}, :date_value::date";
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters(sql)).isEqualTo(sql);
    }

    @Test void leavesInvalidOrIncompleteSqlToExistingSqlValidation() {
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters(null)).isNull();
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters("SELECT '{{name}}"))
            .isEqualTo("SELECT '{{name}}");
        assertThat(DatabaseQueryParameterSyntax.toNamedParameters("'" )).isEqualTo("'");
    }
}
