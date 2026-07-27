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
            new EnterpriseMetadataSearchService(catalog, properties, openSearch, classifier, vectorizer);

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
            .containsEntry("source", "enterprise_field_catalog");
    }
}
