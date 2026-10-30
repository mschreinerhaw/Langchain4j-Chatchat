package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real isolated Python process with pinned SQLGlot. No database or model. */
@EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
class SqlGlotToolchainTest {
    @Test void distinguishesFieldValidationFromBusinessEvidenceNeededByDerivedStatistics(){
        var schema=Map.<String,Object>of("tables",List.of(Map.of("tableName","runs","columns",List.of(Map.of("name","status")))) ,"requireBusinessEvidence",true);
        var derived=parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/rate.sql","SELECT SUM(CASE WHEN status='03' THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0) FROM runs")),schema);
        assertThat(derived).containsEntry("valid",true).containsEntry("fieldValidation","CHECKED").containsEntry("requiresBusinessEvidence",true);
        assertThat(derived.get("businessLiteralValues")).isEqualTo(List.of("03"));
        assertThat(parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/projection.sql","SELECT status FROM runs LIMIT 20")),schema)).containsEntry("requiresBusinessEvidence",false);
    }
    @Test void diagnosticsIdentifySyntaxCategoryFileAndLocationWithoutRunningSource(){
        var result=parser().validate("python",List.of(new SqlOutputBundle.Candidate("src/a.py","# keep\ndef broken(:\n")),Map.of());
        assertThat(result).containsEntry("valid",false).containsEntry("codeExecuted",false);
        var tree=new ObjectMapper().valueToTree(result).path("diagnostics").get(0);
        assertThat(tree.path("path").asText()).isEqualTo("src/a.py");
        assertThat(tree.path("category").asText()).isEqualTo("SYNTAX");
        assertThat(tree.path("line").asInt()).isEqualTo(2);
        assertThat(tree.path("column").asInt()).isGreaterThan(0);
    }
    SqlGlotToolchain parser(){return new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),new ObjectMapper());}
    @Test void realParserReportsStructureReviewWithoutClaimingBusinessAcceptance(){
        var schema=Map.<String,Object>of("tables",List.of(
            Map.of("tableName","accounts","primaryKeys",List.of("id"),"primaryKeyStatus","COLLECTED_VISIBLE",
                "columns",List.of(Map.of("name","id"),Map.of("name","amount"))),
            Map.of("tableName","trades","partitionKeys",List.of("day"),"partitionKeyStatus","COLLECTED_VISIBLE",
                "columns",List.of(Map.of("name","account_id"),Map.of("name","day")))));
        var result=parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/customer.sql",
            "SELECT SUM(a.amount) FROM accounts a JOIN trades t ON a.id=t.account_id")),schema);
        assertThat(result).containsEntry("valid",true).containsEntry("sqlExecuted",false)
            .containsEntry("structureReviewRequired",true).containsEntry("businessValidation","NOT_EVALUATED");
        assertThat(result.get("structuralDiagnostics").toString()).contains("JOIN_FANOUT_AGGREGATION_RISK","MISSING_PARTITION_FILTER","JOIN_RELATION_UNCONFIRMED");
    }
    @Test void parsesAllStudioDialectsAndReturnsNormalizationWithoutExecutingSql(){
        for(String dialect:SqlStudioService.DIALECTS){
            var result=parser().validate(dialect,List.of(new SqlOutputBundle.Candidate("sql/query.sql","SELECT 1 AS value;")),Map.of());
            assertThat(result).containsEntry("valid",true).containsEntry("sqlExecuted",false)
                .containsEntry("version","30.23.0").containsEntry("fieldValidation","NOT_EVALUATED");
            assertThat((List<?>)result.get("normalizedFiles")).hasSize(1);
        }
    }
    @Test void malformedStatementsAndUnsupportedCommandFallbackAreRejected(){
        for(String source:List.of("SELECT * FROM", "SELECT (", "UNKNOWN_ENGINE_COMMAND some text"))
            assertThat(parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/a.sql",source)),Map.of()))
                .containsEntry("valid",false).containsEntry("sqlExecuted",false);
    }
    @Test void suppliedSchemaChecksColumnsRatherThanGuessingUnknownFields(){
        var schema=Map.<String,Object>of("orders",Map.of("id","INT","amount","DECIMAL"));
        assertThat(parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/a.sql","SELECT id FROM orders")),schema))
            .containsEntry("valid",true).containsEntry("fieldValidation","CHECKED");
        assertThat(parser().validate("mysql",List.of(new SqlOutputBundle.Candidate("sql/a.sql","SELECT missing_field FROM orders")),schema))
            .containsEntry("valid",false);
    }
    @Test void unconfiguredParserFailsClosed(){
        assertThatThrownBy(()->new SqlGlotToolchain("",new ObjectMapper()).validate("mysql",List.of(),Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
    }
    @Test void metadataSnapshotsKeepQualifiedTableIdentityAndDoNotTreatCteAliasesAsPhysicalTables(){
        var schema=Map.<String,Object>of("tables",List.of(Map.of("tableName","orders","database","db","schema","public",
            "columns",List.of(Map.of("name","id")))));
        assertThat(parser().validate("postgresql",List.of(new SqlOutputBundle.Candidate("sql/a.sql",
            "WITH selected AS (SELECT id FROM public.orders) SELECT id FROM selected")),schema)).containsEntry("valid",true);
        assertThat(parser().validate("postgresql",List.of(new SqlOutputBundle.Candidate("sql/a.sql",
            "SELECT 1 FROM public.not_delivered")),schema)).containsEntry("valid",false);
        assertThat(parser().validate("postgresql",List.of(new SqlOutputBundle.Candidate("sql/a.sql",
            "SELECT missing FROM public.orders")),schema)).containsEntry("valid",false);
    }
}
