package com.chatchat.agents.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseMetadataSearchBridgeTest {

    private final EnterpriseMetadataSearchBridge bridge =
        new EnterpriseMetadataSearchBridge(new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void combinesSqlMetadataAndQueryTermsIntoVerifiedFieldSearchProjection() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "searchIntent":"verify customer identification fields",
              "queryTerms":["customer identifier","客户标识","mobile phone"],
              "fields":[
                {
                  "fieldName":"cust_id",
                  "fieldCnName":"客户编号",
                  "description":"客户唯一标识",
                  "dataType":"hallucinated_type"
                },
                {
                  "fieldName":"invented_column",
                  "fieldCnName":"虚构字段",
                  "dataType":"varchar"
                }
              ]
            }
            """);
        Map<String, Object> sqlResult = Map.of(
            "schemaVersion", "sql_metadata_search_result.v1",
            "results", List.of(Map.of(
                "location", Map.of("database", "gdp_dwd", "tableName", "customer_base"),
                "columns", List.of(
                    Map.of("columnName", "cust_id", "comment", "客户号", "dataType", "bigint"),
                    Map.of("columnName", "cust_name", "comment", "客户名称", "dataType", "varchar(200)")
                )
            ))
        );

        Map<String, Object> result = bridge.enrich(
            model,
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            Map.of(
                "queryTerms", List.of("客户", "客户信息"),
                "sourceEvidence", List.of(Map.of(
                    "toolName", "mcp_chatchat_mcp_server_sql_metadata_search",
                    "output", sqlResult
                ))
            )
        );

        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0))
            .containsEntry("fieldName", "cust_id")
            .containsEntry("dataType", "bigint")
            .doesNotContainValue("hallucinated_type");
        assertThat((List<String>) result.get("queryTerms"))
            .contains("客户", "客户信息", "customer identifier", "客户标识", "cust_id");
        assertThat((Map<String, Object>) result.get("schemaEvidence"))
            .containsEntry("mode", "MODEL_ASSISTED_SQL_METADATA_PROJECTION")
            .containsEntry("physicalEvidencePreserved", true)
            .containsEntry("sourceFieldCount", 2)
            .containsEntry("projectedFieldCount", 1);
        assertThat(result.get("sourceEvidence")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsCandidateFieldProfileForCreateTableWithoutPhysicalMetadata() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "searchIntent":"design customer profile table",
              "queryTerms":["客户主数据","customer master","证件类型"],
              "fields":[
                {
                  "fieldName":"customer_id",
                  "fieldCnName":"客户编号",
                  "description":"客户唯一编号"
                },
                {
                  "fieldName":"id_type",
                  "fieldCnName":"证件类型",
                  "description":"客户证件类型"
                }
              ]
            }
            """);

        Map<String, Object> result = bridge.enrich(
            model,
            "enterprise_metadata_search",
            Map.of(
                "purpose", "CREATE_TABLE_FIELD_MAPPING",
                "query", "创建客户信息表",
                "queryTerms", List.of("客户", "客户名称", "证件", "状态"),
                "targetObject", Map.of("type", "TABLE", "name", "customer_profile_new")
            )
        );

        assertThat((List<Map<String, Object>>) result.get("fields"))
            .extracting(field -> field.get("fieldName"))
            .containsExactly("customer_id", "id_type");
        assertThat(result).containsEntry("purpose", "CREATE_TABLE_FIELD_MAPPING");
        assertThat((Map<String, Object>) result.get("schemaEvidence"))
            .containsEntry("mode", "MODEL_ASSISTED_CREATE_TABLE_PROJECTION")
            .containsEntry("sourceFieldCount", 0)
            .containsEntry("projectedFieldCount", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotInventCreateTableFieldsForDiscoveryWithoutPhysicalMetadata() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "searchIntent":"verify an existing object against governed metadata",
              "queryTerms":["existing object","metadata verification","field standard"],
              "fields":[
                {"fieldName":"invented_id","fieldCnName":"虚构编号"}
              ]
            }
            """);

        Map<String, Object> result = bridge.enrich(
            model,
            "enterprise_metadata_search",
            Map.of(
                "query", "评估现有对象是否符合标准",
                "queryTerms", List.of("existing_object", "标准核验")
            )
        );

        assertThat(result).doesNotContainKeys("fields", "purpose");
        assertThat((List<String>) result.get("queryTerms")).hasSizeLessThanOrEqualTo(32);
        assertThat((Map<String, Object>) result.get("schemaEvidence"))
            .containsEntry("mode", "MODEL_ASSISTED_DISCOVERY_TERMS")
            .containsEntry("sourceFieldCount", 0)
            .containsEntry("projectedFieldCount", 0);
    }

    @Test
    void modelFailureFallsBackWithoutChangingDeterministicInput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenThrow(new IllegalStateException("model unavailable"));
        Map<String, Object> input = Map.of(
            "query", "客户信息",
            "queryTerms", List.of("客户", "状态")
        );

        assertThat(bridge.enrich(model, "enterprise_metadata_search", input))
            .containsExactlyInAnyOrderEntriesOf(input);
    }
}
