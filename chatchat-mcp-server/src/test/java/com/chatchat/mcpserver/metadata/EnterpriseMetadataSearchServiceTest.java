package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.search.OpenSearchMcpSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseMetadataSearchServiceTest {

    @Test
    void expandsBusinessRootAndReturnsTraceableFieldEvidence() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();
        EnterpriseMetadataWorkbookLoader loader = mock(EnterpriseMetadataWorkbookLoader.class);
        OpenSearchMcpSearchService openSearch = mock(OpenSearchMcpSearchService.class);
        EnterpriseMetadataTaxonomyService taxonomyService = mock(EnterpriseMetadataTaxonomyService.class);
        when(taxonomyService.taxonomy()).thenReturn(new EnterpriseMetadataTaxonomyService.TaxonomySnapshot(
            List.of(new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
                "customer", "customer_account", "客户与账户", "客户和账户管理", "customer",
                List.of(), 10, false, List.of(
                    new EnterpriseMetadataTaxonomyService.TermDefinition("客户", "客户", 1.0D, "CONTAINS", 10)
                )
            )),
            new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
                "fallback", "general_metadata", "通用数据标准", "通用企业数据标准", "common",
                List.of(), 999, true, List.of()
            )
        ));
        EnterpriseMetadataScenarioClassifier classifier =
            new EnterpriseMetadataScenarioClassifier(properties, taxonomyService);
        EnterpriseMetadataVectorizer vectorizer = new EnterpriseMetadataVectorizer(properties);
        when(openSearch.enabled()).thenReturn(false);
        properties.setSourceLocationPattern("memory:test");
        when(loader.load("memory:test")).thenReturn(List.of(
            new EnterpriseMetadataRecord(
                "T001", "metadata_term", "enterprise_term_dictionary",
                "客户", "CUST", "主体", "active", "terms.xlsx#terms",
                Map.of("englishName", "Customer", "abbreviation", "CUST")
            ),
            new EnterpriseMetadataRecord(
                "F001", "metadata_field", "enterprise_field_catalog",
                "客户编码", "CUST_NUM", "客户唯一标识", "标准", "fields.xlsx#fields",
                Map.of("dataType", "字符型", "length", "32")
            )
        ));
        EnterpriseMetadataCatalog catalog =
            new EnterpriseMetadataCatalog(properties, loader, classifier, vectorizer, openSearch);
        catalog.refresh();
        EnterpriseMetadataSearchService service =
            new EnterpriseMetadataSearchService(catalog, properties, openSearch, classifier, vectorizer,
                EnterpriseMetadataTestProperties.policyService());

        Map<String, Object> response = service.search(new EnterpriseMetadataSearchService.SearchRequest(
            "客户信息", List.of("metadata_field"), List.of("标准"), 10));

        assertThat(response)
            .containsEntry("schemaVersion", EnterpriseMetadataSearchService.RESULT_SCHEMA_VERSION)
            .containsEntry("backend", "memory")
            .containsEntry("count", 1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) response.get("evidenceObjects");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("technicalName", "CUST_NUM")
            .containsEntry("dataType", "字符型");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0))
            .containsEntry("type", "metadata_field")
            .containsEntry("evidenceType", "STANDARD_FIELD_REFERENCE")
            .containsEntry("evidenceRole", "STANDARD_EVIDENCE")
            .containsKey("targetConcept")
            .containsEntry("source", "enterprise_field_catalog");
        Map<String, Object> evidenceBundle = (Map<String, Object>) response.get("evidenceBundle");
        assertThat(evidenceBundle)
            .containsEntry("contractVersion", "enterprise_metadata_evidence_bundle.v1")
            .containsKey("factEvidence")
            .containsKey("standardEvidence")
            .containsKey("inferenceEvidence")
            .containsKey("reasoningContract");
        assertThat((Map<String, Object>) evidenceBundle.get("factEvidence"))
            .containsEntry("status", "NOT_PROVIDED_BY_THIS_TOOL")
            .containsEntry("items", List.of());
        assertThat((Map<String, Object>) evidenceBundle.get("standardEvidence"))
            .containsEntry("status", "DATA_RETURNED")
            .containsEntry("count", 1);
    }

    @Test
    void enterpriseMetadataSearchAlwaysRetrievesFieldsTermsAndDictionaries() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();
        EnterpriseMetadataWorkbookLoader loader = mock(EnterpriseMetadataWorkbookLoader.class);
        OpenSearchMcpSearchService openSearch = mock(OpenSearchMcpSearchService.class);
        EnterpriseMetadataTaxonomyService taxonomyService = mock(EnterpriseMetadataTaxonomyService.class);
        when(taxonomyService.taxonomy()).thenReturn(new EnterpriseMetadataTaxonomyService.TaxonomySnapshot(
            List.of(),
            new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
                "fallback", "general_metadata", "General", "General metadata", "common",
                List.of(), 999, true, List.of()
            )
        ));
        EnterpriseMetadataScenarioClassifier classifier =
            new EnterpriseMetadataScenarioClassifier(properties, taxonomyService);
        EnterpriseMetadataVectorizer vectorizer = new EnterpriseMetadataVectorizer(properties);
        when(openSearch.enabled()).thenReturn(false);
        properties.setSourceLocationPattern("memory:test");
        when(loader.load("memory:test")).thenReturn(List.of(
            new EnterpriseMetadataRecord(
                "F001", "metadata_field", "enterprise_field_catalog",
                "Customer Number", "CUST_NUM", "Customer identifier", "active", "fields.xlsx#fields",
                Map.of("dataType", "string")
            ),
            new EnterpriseMetadataRecord(
                "T001", "metadata_term", "enterprise_term_dictionary",
                "Customer", "CUST", "Customer root term", "active", "terms.xlsx#terms",
                Map.of("englishName", "Customer", "abbreviation", "CUST")
            ),
            new EnterpriseMetadataRecord(
                "D001:1", "metadata_dictionary", "enterprise_term_dictionary",
                "Customer Type", "CUSTOMER_TYPE", "Customer dictionary value", "active", "dictionary.xlsx#dictionary",
                Map.of("code", "1", "codeDescription", "Individual customer")
            )
        ));
        EnterpriseMetadataCatalog catalog =
            new EnterpriseMetadataCatalog(properties, loader, classifier, vectorizer, openSearch);
        catalog.refresh();
        EnterpriseMetadataSearchService service =
            new EnterpriseMetadataSearchService(catalog, properties, openSearch, classifier, vectorizer,
                EnterpriseMetadataTestProperties.policyService());

        Map<String, Object> filtered = service.search(new EnterpriseMetadataSearchService.SearchRequest(
            "customer", List.of("metadata_field"), List.of(), 10));

        assertThat(filtered)
            .containsEntry("schemaVersion", EnterpriseMetadataSearchService.RESULT_SCHEMA_VERSION)
            .containsEntry("count", 1)
            .doesNotContainKey("requiredRetrieval");
        assertThat((List<Map<String, Object>>) filtered.get("results"))
            .extracting(item -> item.get("metadataType"))
            .containsExactly("metadata_field");

        Map<String, Object> response = service.searchRequiredBundle(
            new EnterpriseMetadataSearchService.SearchRequest(
                "customer", List.of("metadata_field"), List.of(), 10));

        assertThat(response)
            .containsEntry("schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION)
            .containsEntry("count", 3);
        assertThat((List<Map<String, Object>>) response.get("results")).hasSize(3);
        Map<String, Object> evidenceBundle = (Map<String, Object>) response.get("evidenceBundle");
        Map<String, Object> standardEvidence =
            (Map<String, Object>) evidenceBundle.get("standardEvidence");
        assertThat(standardEvidence)
            .containsEntry("count", 3)
            .containsEntry("selectedCount", 1);
        assertThat((List<Map<String, Object>>) standardEvidence.get("items")).hasSize(1);
        assertThat((Map<String, Object>) evidenceBundle.get("reasoningContract"))
            .containsEntry("candidateReturnPolicy", "ALL_RETRIEVED_CANDIDATES_IN_RESULTS")
            .containsEntry("reasoningSelectionPolicy", "HIGHEST_CONFIDENCE_ONE");
        assertThat((Map<String, Object>) response.get("evidenceCoverage"))
            .containsEntry("contractVersion", "enterprise_metadata_evidence_coverage.v2")
            .containsEntry("scope", "ENTERPRISE_FIELD_METADATA")
            .containsEntry("evidenceRole", "STANDARD_REFERENCE_DATA")
            .containsEntry("declarationSource", "metadata_governance_policy")
            .containsEntry("policyVersion", "test-policy-v1");
        assertThat((List<String>) ((Map<String, Object>) response.get("evidenceCoverage"))
            .get("returnedEvidenceTypes"))
            .contains("standard field metadata", "business term and root metadata",
                "code dictionary metadata");
        assertThat((Map<String, Object>) response.get("evidenceCoverage"))
            .doesNotContainKeys("supportedClaims", "notAssessedClaims", "fullTableDesignConformanceSupported");
        assertThat((List<String>) response.get("requiredTypes"))
            .containsExactly("metadata_field", "metadata_term", "metadata_dictionary");
        Map<String, Object> countsByType = (Map<String, Object>) response.get("countsByType");
        assertThat(countsByType)
            .containsEntry("metadata_field", 1)
            .containsEntry("metadata_term", 1)
            .containsEntry("metadata_dictionary", 1);
        Map<String, Object> requiredRetrieval = (Map<String, Object>) response.get("requiredRetrieval");
        assertThat(requiredRetrieval)
            .containsEntry("allTypesAttempted", true)
            .containsEntry("evidenceComplete", true)
            .containsEntry("emptyTypes", List.of())
            .containsEntry("omittedTypes", List.of())
            .containsEntry("policy", "enterprise_metadata_search_always_queries_standard_fields_terms_and_dictionaries");

        Map<String, Object> limited = service.searchRequiredBundle(
            new EnterpriseMetadataSearchService.SearchRequest(
                "customer", List.of(), List.of(), 2));
        assertThat(limited).containsEntry("count", 2);
        Map<String, Object> limitedRetrieval =
            (Map<String, Object>) limited.get("requiredRetrieval");
        assertThat(limitedRetrieval)
            .containsEntry("allTypesAttempted", true)
            .containsEntry("evidenceComplete", false)
            .containsEntry("emptyTypes", List.of())
            .containsEntry("omittedTypes", List.of("metadata_dictionary"));
    }
}
