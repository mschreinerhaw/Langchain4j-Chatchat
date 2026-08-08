package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EnterpriseMetadataMcpToolPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void routesCompleteFieldBundleThroughSingleEnterpriseMetadataSearchCapability() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher = publisher(matchingService, searchService);
        Map<String, Object> request = Map.of(
            "query", "client_id customer identifier liability_amount",
            "purpose", "EXISTING_TABLE_METADATA_ALIGNMENT",
            "targetObject", Map.of(
                "type", "TABLE",
                "name", "gdp_ads.ads_ids_clr_acc_liab_d_i"
            ),
            "fields", List.of(
                Map.of(
                    "fieldName", "client_id",
                    "fieldCnName", "客户编号",
                    "dataType", "varchar(64)"
                ),
                Map.of(
                    "fieldName", "liability_amount",
                    "fieldCnName", "负债金额",
                    "dataType", "decimal(18,2)"
                )
            )
        );
        when(matchingService.match(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataMatchingService.SCHEMA_VERSION,
            "success", true,
            "coverage", Map.of(
                "inputFieldCount", 2,
                "processedFieldCount", 2,
                "allFieldsProcessed", true
            )
        ));

        Map<String, Object> result = publisher.executeSearch(request);

        assertThat(result)
            .containsEntry("success", true)
            .containsEntry("invokedCapability", "enterprise_metadata_search")
            .containsEntry("retrievalMode", "UNIFIED_FIELD_EVIDENCE_BUNDLE");
        assertThat((Map<String, Object>) result.get("coverage"))
            .containsEntry("inputFieldCount", 2)
            .containsEntry("processedFieldCount", 2)
            .containsEntry("allFieldsProcessed", true);
        verify(matchingService).match(any());
        verifyNoInteractions(searchService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryTermsDiscoverEnterpriseMetadataForNewTableWithoutDraftFields() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher = publisher(matchingService, searchService);
        when(searchService.searchRequiredBundle(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
            "success", true,
            "count", 3,
            "backend", "opensearch",
            "results", List.of(
                Map.of("metadataType", "standard_field", "name", "客户号"),
                Map.of("metadataType", "enterprise_term", "name", "客户"),
                Map.of("metadataType", "code_dictionary", "name", "客户状态")
            ),
            "evidenceObjects", List.of()
        ));

        Map<String, Object> result = publisher.executeSearch(Map.of(
            "queryTerms", List.of(
                "客户", "客户信息", "customer", "客户号", "客户名称", "证件", "地址", "手机", "状态"
            ),
            "requestId", "new-table-1"
        ));

        assertThat(result)
            .containsEntry("success", true)
            .containsEntry("invokedCapability", "enterprise_metadata_search")
            .containsEntry("operationMode", "ENTERPRISE_METADATA_DISCOVERY")
            .containsEntry("count", 3);
        assertThat((List<String>) result.get("inputTerms"))
            .contains("客户", "客户号", "客户名称", "手机", "状态");
        ArgumentCaptor<EnterpriseMetadataSearchService.SearchRequest> request =
            ArgumentCaptor.forClass(EnterpriseMetadataSearchService.SearchRequest.class);
        verify(searchService).searchRequiredBundle(request.capture());
        assertThat(request.getValue().query())
            .contains("客户", "客户信息", "customer", "客户号", "客户名称", "证件", "地址", "手机", "状态");
        verifyNoInteractions(matchingService);
    }

    @Test
    void queryOnlyAlsoUsesDiscoveryInsteadOfDemandingFieldSchema() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher = publisher(matchingService, searchService);
        when(searchService.searchRequiredBundle(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
            "success", true,
            "count", 0,
            "backend", "opensearch",
            "results", List.of(),
            "evidenceObjects", List.of()
        ));

        Map<String, Object> result = publisher.executeSearch(Map.of(
            "query", "设计客户信息表",
            "requestId", "new-table-query-1"
        ));

        assertThat(result)
            .containsEntry("success", true)
            .containsEntry("operationMode", "ENTERPRISE_METADATA_DISCOVERY")
            .containsEntry("count", 0);
        verify(searchService).searchRequiredBundle(any());
        verifyNoInteractions(matchingService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyKeywordsRemainSearchEvidenceDuringProtocolMigration() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher = publisher(matchingService, searchService);
        when(searchService.searchRequiredBundle(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
            "success", true,
            "count", 1,
            "results", List.of(),
            "evidenceObjects", List.of()
        ));

        Map<String, Object> result = publisher.executeSearch(Map.of(
            "keywords", List.of("var_scr_code_info", "变量评分代码信息", "评分代码"),
            "requestId", "legacy-keywords-1"
        ));

        assertThat(result)
            .containsEntry("success", true)
            .containsEntry("operationMode", "ENTERPRISE_METADATA_DISCOVERY");
        assertThat((List<String>) result.get("inputTerms"))
            .containsExactly("var_scr_code_info", "变量评分代码信息", "评分代码");
        verify(searchService).searchRequiredBundle(any());
        verifyNoInteractions(matchingService);
    }

    @Test
    void nonexistentTargetTableFallsBackFromSchemaLookupToTermDiscovery() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        SqlMetadataSearchService sqlSearchService = mock(SqlMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher =
            publisher(matchingService, searchService, sqlSearchService);
        when(sqlSearchService.search(any())).thenReturn(Map.of());
        when(searchService.searchRequiredBundle(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
            "success", true,
            "count", 2,
            "backend", "opensearch",
            "results", List.of(),
            "evidenceObjects", List.of()
        ));

        Map<String, Object> result = publisher.executeSearch(Map.of(
            "targetObject", Map.of("type", "TABLE", "name", "customer_profile_new"),
            "queryTerms", List.of("customer", "customer name", "mobile", "status"),
            "requestId", "new-table-target-1"
        ));

        assertThat(result)
            .containsEntry("success", true)
            .containsEntry("operationMode", "ENTERPRISE_METADATA_DISCOVERY")
            .containsEntry("count", 2);
        verify(sqlSearchService).search(any());
        verify(searchService).searchRequiredBundle(any());
        verifyNoInteractions(matchingService);
    }

    @Test
    void rejectsOnlyWhenNeitherFieldsNorSearchTermsCanBeResolved() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataSearchService searchService = mock(EnterpriseMetadataSearchService.class);
        EnterpriseMetadataMcpToolPublisher publisher = publisher(matchingService, searchService);

        Map<String, Object> result = publisher.executeSearch(Map.of("requestId", "missing-input-1"));

        assertThat(result)
            .containsEntry("success", false)
            .containsEntry("errorCode", "ENTERPRISE_METADATA_INPUT_REQUIRED");
        verifyNoInteractions(searchService, matchingService);
    }

    @Test
    void refreshPublishesSearchOnlyAndRemovesRetiredMatchTool() {
        McpSyncServer server = mock(McpSyncServer.class);
        EnterpriseMetadataMcpToolPublisher publisher = new EnterpriseMetadataMcpToolPublisher(
            server,
            mock(EnterpriseMetadataMatchingService.class),
            mock(EnterpriseMetadataSearchService.class),
            new EnterpriseMetadataRequestAdapter(mock(SqlMetadataSearchService.class)),
            new EnterpriseMetadataProperties(),
            EnterpriseMetadataTestProperties.policyService()
        );

        publisher.refresh();

        ArgumentCaptor<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tool =
            ArgumentCaptor.forClass(
                io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        verify(server).removeTool(EnterpriseMetadataMcpToolPublisher.TOOL_NAME);
        verify(server).removeTool(EnterpriseMetadataMcpToolPublisher.RETIRED_MATCH_TOOL_NAME);
        verify(server).addTool(tool.capture());
        assertThat(tool.getValue().tool().name())
            .isEqualTo(EnterpriseMetadataMcpToolPublisher.TOOL_NAME);
        Map<String, Object> properties = tool.getValue().tool().inputSchema().properties();
        assertThat((Map<String, Object>) properties.get("limit"))
            .containsEntry("minimum", 1)
            .doesNotContainKey("maximum");
        assertThat((Map<String, Object>) properties.get("candidateLimitPerType"))
            .containsEntry("minimum", 1)
            .doesNotContainKey("maximum");
        verify(server).notifyToolsListChanged();
    }

    private EnterpriseMetadataMcpToolPublisher publisher(
        EnterpriseMetadataMatchingService matchingService,
        EnterpriseMetadataSearchService searchService
    ) {
        return publisher(matchingService, searchService, mock(SqlMetadataSearchService.class));
    }

    private EnterpriseMetadataMcpToolPublisher publisher(
        EnterpriseMetadataMatchingService matchingService,
        EnterpriseMetadataSearchService searchService,
        SqlMetadataSearchService sqlSearchService
    ) {
        return new EnterpriseMetadataMcpToolPublisher(
            mock(McpSyncServer.class),
            matchingService,
            searchService,
            new EnterpriseMetadataRequestAdapter(sqlSearchService),
            mock(EnterpriseMetadataProperties.class),
            mock(MetadataGovernancePolicyService.class)
        );
    }
}
