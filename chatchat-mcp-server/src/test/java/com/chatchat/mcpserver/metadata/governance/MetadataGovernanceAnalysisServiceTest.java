package com.chatchat.mcpserver.metadata.governance;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataTestProperties;

import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataCatalog;
import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataRecord;

import com.chatchat.mcpserver.sql.metadata.SqlMetadataSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataGovernanceAnalysisServiceTest {

    private EnterpriseMetadataCatalog catalog;
    private SqlMetadataSearchService sqlMetadataSearchService;
    private MetadataGovernanceAnalysisService service;
    private MetadataGovernancePolicyService policyService;

    @BeforeEach
    void setUp() {
        catalog = mock(EnterpriseMetadataCatalog.class);
        sqlMetadataSearchService = mock(SqlMetadataSearchService.class);
        policyService = EnterpriseMetadataTestProperties.policyService();
        when(catalog.records()).thenReturn(standards());
        when(catalog.status()).thenReturn(Map.of(
            "recordCount", standards().size(),
            "source", "database"
        ));
        service = new MetadataGovernanceAnalysisService(catalog, sqlMetadataSearchService, policyService);
    }

    @Test
    void annotatesCreateTableWithFieldsTermsAndDictionaryWithoutExecutingIt() {
        Map<String, Object> result = service.annotateDdl("""
            CREATE TABLE customer_profile (
              customer_id BIGINT NOT NULL COMMENT '客户编号',
              customer_status VARCHAR(2) NOT NULL COMMENT '客户状态',
              legacy_flag VARCHAR(8)
            )
            """);

        assertThat(result)
            .containsEntry("schemaVersion", MetadataGovernanceAnalysisService.ANNOTATION_SCHEMA_VERSION)
            .containsEntry("executionStatus", "NOT_EXECUTED")
            .containsEntry("table", "customer_profile")
            .containsEntry("columnCount", 3)
            .containsEntry("standardFieldMatchedCount", 2L)
            .containsEntry("standardFieldUnmatchedCount", 1L);

        List<Map<String, Object>> columns = maps(result.get("columns"));
        assertThat(field(columns.get(0), "technicalName")).isEqualTo("customer_id");
        assertThat(maps(columns.get(0).get("standardTerms")))
            .extracting(value -> value.get("technicalName"))
            .containsExactly("CUST", "ID");
        assertThat(maps(columns.get(1).get("standardDictionaries")))
            .extracting(value -> value.get("dictionaryId"))
            .contains("customer_status");
        assertThat(columns.get(2))
            .containsEntry("annotationStatus", "UNMATCHED")
            .containsEntry("unmatchedNameTerms", List.of("legacy", "flag"));
    }

    @Test
    void comparesDdlAndReportsOnlyEvidenceBackedDifferences() {
        Map<String, Object> result = service.compareDdl("""
            CREATE TABLE customer_profile (
              customer_id BIGINT NOT NULL,
              customer_status VARCHAR(1) NULL,
              legacy_flag VARCHAR(8)
            )
            """);

        assertThat(result)
            .containsEntry("schemaVersion", MetadataGovernanceAnalysisService.COMPARISON_SCHEMA_VERSION)
            .containsEntry("analysisSource", "ddl")
            .containsEntry("conforms", false);
        List<Map<String, Object>> differences = maps(result.get("differences"));
        assertThat(differences).extracting(value -> value.get("code"))
            .contains("DATA_TYPE_MISMATCH", "NULLABILITY_MISMATCH", "STANDARD_FIELD_MISSING",
                "TERM_NOT_STANDARD");
        assertThat(differences.stream()
            .filter(value -> "customer_id".equals(value.get("column"))))
            .isEmpty();
    }

    @Test
    void comparesRegisteredTableUsingRetrievedPhysicalColumns() {
        when(sqlMetadataSearchService.search(argThat(arguments ->
            "ods.customer_profile".equals(arguments.get("tableName"))
                && Boolean.TRUE.equals(arguments.get("includeColumns")))))
            .thenReturn(Map.of(
                "schemaVersion", SqlMetadataSearchService.RESULT_SCHEMA_VERSION,
                "searchRequestId", "search-1",
                "topTables", List.of(Map.of(
                    "location", Map.of(
                        "qualifiedName", "ods.customer_profile",
                        "tableName", "customer_profile"
                    ),
                    "columns", List.of(
                        Map.of("name", "customer_id", "columnType", "BIGINT", "nullable", false),
                        Map.of("name", "customer_status", "columnType", "VARCHAR(2)", "nullable", false)
                    )
                ))
            ));

        Map<String, Object> result = service.compareRegisteredTable(Map.of(
            "tableName", "ods.customer_profile",
            "assetId", "warehouse-1"
        ));

        assertThat(result)
            .containsEntry("analysisSource", "registered_table")
            .containsEntry("table", "ods.customer_profile")
            .containsEntry("differenceCount", 0)
            .containsEntry("conforms", true);
        assertThat(objectMap(result.get("sourceEvidence")))
            .containsEntry("searchRequestId", "search-1");
    }

    @Test
    void rejectsNonCreateTableSql() {
        assertThatThrownBy(() -> service.annotateDdl("DROP TABLE customer_profile"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CREATE TABLE");
    }

    @Test
    void reportsMissingDictionaryMappingWhenStandardFieldDeclaresValueRange() {
        when(catalog.records()).thenReturn(standards().stream()
            .filter(record -> !"metadata_dictionary".equals(record.metadataType()))
            .toList());

        Map<String, Object> result = service.compareDdl("""
            CREATE TABLE customer_profile (
              customer_status VARCHAR(2) NOT NULL
            )
            """);

        assertThat(maps(result.get("differences")))
            .extracting(value -> value.get("code"))
            .containsExactly("DICTIONARY_MAPPING_MISSING");
    }

    private List<EnterpriseMetadataRecord> standards() {
        return List.of(
            new EnterpriseMetadataRecord(
                "F001", "metadata_field", "enterprise_field_catalog",
                "客户编号", "customer_id", "客户唯一编号", "active", "test",
                Map.of("dataType", "BIGINT", "nullable", "N")
            ),
            new EnterpriseMetadataRecord(
                "F002", "metadata_field", "enterprise_field_catalog",
                "客户状态", "customer_status", "客户当前状态", "active", "test",
                Map.of("dataType", "VARCHAR", "length", "2", "nullable", "N",
                    "valueRange", "customer_status")
            ),
            new EnterpriseMetadataRecord(
                "T001", "metadata_term", "enterprise_term_dictionary",
                "客户", "CUST", "客户词根", "active", "test",
                Map.of("englishName", "customer", "abbreviation", "CUST")
            ),
            new EnterpriseMetadataRecord(
                "T002", "metadata_term", "enterprise_term_dictionary",
                "编号", "ID", "编号词根", "active", "test",
                Map.of("englishName", "identifier", "abbreviation", "ID")
            ),
            new EnterpriseMetadataRecord(
                "T003", "metadata_term", "enterprise_term_dictionary",
                "状态", "STATUS", "状态词根", "active", "test",
                Map.of("englishName", "status", "abbreviation", "STATUS")
            ),
            new EnterpriseMetadataRecord(
                "D001:1", "metadata_dictionary", "enterprise_term_dictionary",
                "客户状态", "customer_status", "正常", "active", "test",
                Map.of("dictionaryId", "customer_status", "code", "01",
                    "codeDescription", "正常")
            )
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private Object field(Map<String, Object> column, String name) {
        return objectMap(column.get("standardField")).get(name);
    }
}
