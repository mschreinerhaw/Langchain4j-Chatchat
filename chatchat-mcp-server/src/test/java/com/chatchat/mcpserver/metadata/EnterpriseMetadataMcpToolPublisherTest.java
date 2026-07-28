package com.chatchat.mcpserver.metadata;

import io.modelcontextprotocol.server.McpSyncServer;
import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import org.junit.jupiter.api.Test;

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
    void routesCompleteFieldBundleThroughSingleEnterpriseMetadataSearchCapability() {
        EnterpriseMetadataMatchingService matchingService = mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataMcpToolPublisher publisher = new EnterpriseMetadataMcpToolPublisher(
            mock(McpSyncServer.class),
            matchingService,
            new EnterpriseMetadataRequestAdapter(mock(SqlMetadataSearchService.class)),
            mock(EnterpriseMetadataProperties.class),
            mock(MetadataGovernancePolicyService.class)
        );
        Map<String, Object> request = Map.of(
            "query", "client_id 客户编号 liability_amount 负债金额",
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
    }

    @Test
    void neverFallsBackToCatalogSearchWhenFieldSchemaCannotBeResolved() {
        EnterpriseMetadataMatchingService matchingService =
            mock(EnterpriseMetadataMatchingService.class);
        EnterpriseMetadataMcpToolPublisher publisher = new EnterpriseMetadataMcpToolPublisher(
            mock(McpSyncServer.class),
            matchingService,
            new EnterpriseMetadataRequestAdapter(mock(SqlMetadataSearchService.class)),
            mock(EnterpriseMetadataProperties.class),
            mock(MetadataGovernancePolicyService.class)
        );

        Map<String, Object> result = publisher.executeSearch(Map.of(
            "query", "无法解析为字段或物理表的普通查询",
            "requestId", "missing-fields-1"
        ));

        assertThat(result)
            .containsEntry("success", false)
            .containsEntry("errorCode", "FIELD_SCHEMA_REQUIRED")
            .containsEntry("invokedCapability", "enterprise_metadata_search")
            .containsEntry("retrievalMode", "UNIFIED_FIELD_EVIDENCE_BUNDLE");
        assertThat((Map<String, Object>) result.get("coverage"))
            .containsEntry("inputFieldCount", 0)
            .containsEntry("processedFieldCount", 0)
            .containsEntry("allFieldsProcessed", false);
        assertThat((List<Map<String, Object>>) result.get("fieldMatches")).isEmpty();
        assertThat((List<Map<String, Object>>) result.get("evidenceObjects")).isEmpty();
        verifyNoInteractions(matchingService);
    }
}
