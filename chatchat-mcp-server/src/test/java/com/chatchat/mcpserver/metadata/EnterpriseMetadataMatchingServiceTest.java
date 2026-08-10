package com.chatchat.mcpserver.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseMetadataMatchingServiceTest {

    private EnterpriseMetadataSearchService searchService;
    private MetadataGovernanceAnalysisService governanceAnalysisService;
    private EnterpriseMetadataMatchingService service;

    @BeforeEach
    void setUp() {
        searchService = mock(EnterpriseMetadataSearchService.class);
        governanceAnalysisService = mock(MetadataGovernanceAnalysisService.class);
        MetadataGovernancePolicyService policyService =
            mock(MetadataGovernancePolicyService.class);
        MetadataGovernancePolicy policy = policy();
        when(policyService.current()).thenReturn(policy);
        when(policyService.evidenceCoverage()).thenReturn(MetadataGovernancePolicyService.evidenceCoverage(
            policy.getEvidenceCoverage(), policy.getVersion()));
        when(searchService.search(any())).thenAnswer(invocation ->
            searchResult(invocation.getArgument(0)));
        EnterpriseMetadataCatalog catalog = mock(EnterpriseMetadataCatalog.class);
        when(catalog.records()).thenReturn(List.of(
            new EnterpriseMetadataRecord(
                "T-CUSTOMER", "metadata_term", "enterprise_term_dictionary",
                "客户", "customer", "客户业务词根", "active", "terms#1",
                Map.of("englishName", "customer", "abbreviation", "cust")
            ),
            new EnterpriseMetadataRecord(
                "T-NAME", "metadata_term", "enterprise_term_dictionary",
                "姓名", "name", "名称词根", "active", "terms#2",
                Map.of("englishName", "name", "abbreviation", "nm")
            ),
            new EnterpriseMetadataRecord(
                "F-CUSTOMER-NAME", "metadata_field", "enterprise_field_catalog",
                "客户姓名", "customer_name", "客户姓名标准字段", "active", "fields#1",
                Map.of("englishName", "Customer Name", "dataType", "VARCHAR")
            )
        ));
        service = new EnterpriseMetadataMatchingService(
            new CatalogMetadataEvidenceProvider(searchService),
            governanceAnalysisService,
            policyService,
            new EnterpriseMetadataProperties(),
            catalog
        );
    }

    @Test
    void searchesEveryExplicitFieldAgainstFieldsTermsAndDictionaries() {
        Map<String, Object> result = service.match(Map.of(
            "requestId", "req-001",
            "purpose", "CREATE_TABLE_FIELD_MAPPING",
            "targetObject", Map.of("type", "TABLE", "name", "客户画像表"),
            "fields", List.of(
                Map.of(
                    "fieldName", "customer_name",
                    "fieldCnName", "客户姓名",
                    "dataType", "VARCHAR",
                    "description", "客户名称"
                ),
                Map.of(
                    "fieldName", "open_date",
                    "fieldCnName", "开户日期",
                    "dataType", "DATE"
                )
            ),
            "candidateLimitPerType", 3
        ));

        assertThat(result)
            .containsEntry("schemaVersion", "enterprise_metadata_field_discovery.v1")
            .containsEntry("requestId", "req-001")
            .containsEntry("purpose", "CREATE_TABLE_FIELD_MAPPING");
        assertThat(map(result.get("coverage")))
            .containsEntry("inputFieldCount", 2)
            .containsEntry("processedFieldCount", 2)
            .containsEntry("allFieldsProcessed", true)
            .containsEntry("perFieldTypeRetrieval", true);
        Map<String, Object> reviewContract = map(result.get("reviewContract"));
        assertThat(reviewContract)
            .containsEntry("candidateReturnPolicy", "ALL_RETRIEVED_CANDIDATES");
        assertThat(map(reviewContract.get("reasoningCandidateSelection")))
            .containsEntry("strategy", "HIGHEST_SCORE")
            .containsEntry("maximumSelectedPerFieldAndMetadataType", 1)
            .containsEntry("tieBreaker", "PROVIDER_ORDER");

        List<Map<String, Object>> fields = maps(result.get("fieldMatches"));
        assertThat(fields).hasSize(2);
        assertThat(map(fields.get(0).get("matchesByType")).keySet())
            .containsExactly(
                "metadata_field", "metadata_term", "metadata_dictionary");
        assertThat(maps(fields.get(0).get("standardFields")).get(0))
            .containsEntry("matchType", "EXACT_ENGLISH")
            .containsEntry("matchLevel", "EXACT")
            .containsEntry("recommendation", "REUSE");
        assertThat(map(fields.get(0).get("analysis")))
            .containsEntry("canReuse", true)
            .containsEntry("recommendation", "REUSE");
        assertThat(maps(result.get("evidenceObjects")))
            .allSatisfy(evidence -> {
                assertThat(evidence).containsEntry(
                    "contractVersion", "evidence_object_v1");
                assertThat(map(evidence.get("subject")))
                    .containsEntry("kind", "FIELD");
            });

        ArgumentCaptor<EnterpriseMetadataSearchService.SearchRequest> requests =
            ArgumentCaptor.forClass(EnterpriseMetadataSearchService.SearchRequest.class);
        verify(searchService, org.mockito.Mockito.times(6)).search(requests.capture());
        assertThat(requests.getAllValues())
            .allSatisfy(request -> assertThat(request.types()).hasSize(1));
        assertThat(requests.getAllValues().stream().map(
            EnterpriseMetadataSearchService.SearchRequest::types).distinct())
            .containsExactlyInAnyOrder(
                List.of("metadata_field"),
                List.of("metadata_term"),
                List.of("metadata_dictionary")
            );
        assertThat(requests.getAllValues().get(0).query())
            .contains(
                "customer_name", "customer name", "客户姓名", "客户名称",
                "customer", "cust", "name", "nm", "cust_nm");
        Map<String, Object> providerRequest = map(
            map(result.get("providerExchange")).get("request"));
        List<Map<String, Object>> providerFields = maps(providerRequest.get("fields"));
        assertThat(maps(providerFields.get(0).get("tokens")))
            .extracting(token -> token.get("type"))
            .contains("CN", "EN", "ROOT", "ABBR");
        assertThat(providerFields.get(0))
            .containsEntry("standardField", "customer_name");
        assertThat(strings(providerFields.get(0).get("dictionaryTerms")))
            .contains("客户", "customer", "姓名", "name");
        Map<String, Object> providerResponse = map(
            map(result.get("providerExchange")).get("response"));
        Map<String, Object> firstProviderResult =
            maps(providerResponse.get("results")).get(0);
        Map<String, Object> firstProviderCandidate =
            maps(firstProviderResult.get("candidates")).get(0);
        assertThat(firstProviderCandidate)
            .containsEntry("columnName", "customer_name")
            .containsEntry("columnCnName", "客户姓名");
        assertThat(map(firstProviderCandidate.get("matchResult")))
            .containsEntry("level", "EXACT");
    }

    @Test
    void usesCallerLimitForEverySuppliedFieldWithoutApplyingLegacyFixedCap() {
        Map<String, Object> result = service.match(Map.of(
            "fields", List.of(
                Map.of("fieldName", "customer_name"),
                Map.of("fieldName", "open_date")
            ),
            "limit", 135
        ));

        assertThat(map(result.get("coverage")))
            .containsEntry("inputFieldCount", 2)
            .containsEntry("processedFieldCount", 2)
            .containsEntry("allFieldsProcessed", true);
        ArgumentCaptor<EnterpriseMetadataSearchService.SearchRequest> requests =
            ArgumentCaptor.forClass(EnterpriseMetadataSearchService.SearchRequest.class);
        verify(searchService, org.mockito.Mockito.times(6)).search(requests.capture());
        assertThat(requests.getAllValues())
            .allSatisfy(request -> assertThat(request.limit()).isEqualTo(135));
    }

    @Test
    void expandsEveryColumnFromCreateTableBeforeSearching() {
        when(governanceAnalysisService.annotateDdl(any())).thenReturn(governanceResult(
            "metadata_ddl_annotation.v1", "DDL", "customer_profile"));

        Map<String, Object> result = service.match(Map.of(
            "ddl", "create table customer_profile (...)"
        ));

        verify(governanceAnalysisService).annotateDdl(
            "create table customer_profile (...)");
        assertThat(map(result.get("sourceSchema")))
            .containsEntry("mode", "DDL")
            .containsEntry("table", "customer_profile")
            .containsEntry("fieldCount", 2);
        assertThat(maps(result.get("fieldMatches"))).hasSize(2);
        assertThat(map(result.get("evidenceCoverage")))
            .containsEntry("contractVersion", "enterprise_metadata_evidence_coverage.v2")
            .containsEntry("evidenceRole", "STANDARD_REFERENCE_DATA")
            .containsEntry("declarationSource", "metadata_governance_policy")
            .containsEntry("policyVersion", "test-policy-v1");
        assertThat(map(result.get("fieldComparisonEvidence")))
            .containsEntry("scope", "FIELD_METADATA_COMPARISON")
            .containsEntry("differenceCount", 1)
            .doesNotContainKeys("conformsWithinScope", "fullTableDesignConformance");
        verify(searchService, org.mockito.Mockito.times(6)).search(any());
    }

    @Test
    void retrievesCompleteRegisteredTableThenSearchesEachColumn() {
        when(governanceAnalysisService.compareRegisteredTable(any())).thenReturn(
            governanceResult(
                "metadata_standard_comparison.v1",
                "registered_table",
                "crm_customer_info"
            ));

        Map<String, Object> result = service.match(Map.of(
            "tableName", "crm_customer_info",
            "assetId", "asset-crm"
        ));

        verify(governanceAnalysisService).compareRegisteredTable(any());
        assertThat(map(result.get("sourceSchema")))
            .containsEntry("mode", "REGISTERED_TABLE")
            .containsEntry("table", "crm_customer_info")
            .containsEntry("fieldCount", 2);
        assertThat(maps(result.get("fieldMatches"))).hasSize(2);
        verify(searchService, org.mockito.Mockito.times(6)).search(any());
    }

    private Map<String, Object> governanceResult(
        String schemaVersion, String source, String table
    ) {
        return Map.of(
            "schemaVersion", schemaVersion,
            "analysisSource", source,
            "table", table,
            "conforms", false,
            "differenceCount", 1,
            "severityCounts", Map.of("WARNING", 1),
            "differences", List.of(Map.of(
                "field", "open_date",
                "code", "NULLABILITY_MISMATCH",
                "severity", "WARNING"
            )),
            "factBoundary", "physical_schema_and_maintained_enterprise_metadata_catalog",
            "columns", List.of(
                Map.of("physical", Map.of(
                    "name", "customer_name",
                    "comment", "客户姓名",
                    "dataType", "VARCHAR(100)",
                    "nullable", false
                )),
                Map.of("physical", Map.of(
                    "name", "open_date",
                    "comment", "开户日期",
                    "dataType", "DATE",
                    "nullable", true
                ))
            )
        );
    }

    private Map<String, Object> searchResult(
        EnterpriseMetadataSearchService.SearchRequest request
    ) {
        String type = request.types().get(0);
        boolean customer = request.query().contains("customer");
        Map<String, Object> record = switch (type) {
            case "metadata_field" -> Map.of(
                "id", customer ? "F-CUSTOMER-NAME" : "F-OPEN-DATE",
                "metadataType", type,
                "name", customer ? "客户姓名" : "开户日期",
                "technicalName", customer ? "customer_name" : "open_date",
                "englishName", customer ? "Customer Name" : "Open Date",
                "dataType", customer ? "VARCHAR" : "DATE",
                "relevanceScore", 1.1D
            );
            case "metadata_term" -> Map.of(
                "id", customer ? "T-CUSTOMER" : "T-OPEN",
                "metadataType", type,
                "name", customer ? "客户" : "开户",
                "technicalName", customer ? "CUST" : "OPEN",
                "relevanceScore", 0.8D
            );
            default -> Map.of(
                "id", customer ? "D-CUSTOMER-TYPE" : "D-DATE-TYPE",
                "metadataType", type,
                "name", customer ? "客户类型字典" : "日期类型字典",
                "technicalName", customer ? "CUSTOMER_TYPE" : "DATE_TYPE",
                "relevanceScore", 0.6D
            );
        };
        return Map.of(
            "results", List.of(record),
            "evidenceObjects", List.of(Map.of(
                "contractVersion", "evidence_object_v1",
                "evidenceId", "EM-" + record.get("id"),
                "type", type,
                "source", "enterprise_metadata_catalog",
                "content", record,
                "confidence", 0.9D,
                "quality", Map.of(
                    "sourceAuthority", "enterprise_standard",
                    "traceable", true
                )
            ))
        );
    }

    private MetadataGovernancePolicy policy() {
        MetadataGovernancePolicy policy = new MetadataGovernancePolicy();
        policy.setVersion("test-policy-v1");
        MetadataGovernancePolicy.MetadataContract contract =
            policy.getMetadataContract();
        contract.setFieldType("metadata_field");
        contract.setTermType("metadata_term");
        contract.setDictionaryType("metadata_dictionary");
        contract.setRequiredBundle(List.of(
            "metadata_field", "metadata_term", "metadata_dictionary"));
        contract.setDataTypeAttribute("dataType");
        contract.setEnglishNameAttribute("englishName");
        contract.setAbbreviationAttribute("abbreviation");
        return policy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) {
        return (List<String>) value;
    }
}
