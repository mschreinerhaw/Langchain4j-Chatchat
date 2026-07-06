package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssetMetadataFactoryTest {

    private final AssetMetadataFactory factory = new AssetMetadataFactory(new ObjectMapper());

    @Test
    void sshMetadataContainsRoutingHintsAndNoConcreteHostAddress() {
        SshHostConfig host = new SshHostConfig();
        host.setId("host-1");
        host.setName("datanode-a");
        host.setTitle("DataNode A");
        host.setToolName("ssh_datanode_a");
        host.setEnvironment("PROD");
        host.setHostname("10.0.0.12");
        host.setRoutingLabelsJson("[\"cluster:hadoop-prod\",\"datanode\"]");
        host.setCapabilitiesJson("[\"ssh\",\"linux_command_execute\"]");
        host.setAllowedCommandsJson("[\"CHECK_UPTIME\"]");

        Map<String, Object> metadata = factory.sshAsset(host);
        Map<?, ?> routingHints = (Map<?, ?>) metadata.get("routingHints");
        Map<?, ?> executionContext = (Map<?, ?>) routingHints.get("executionContext");
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        Map<?, ?> scoreHints = (Map<?, ?>) routingHints.get("selectionScoreHints");
        List<String> labels = ((List<?>) routingHints.get("labels")).stream()
            .map(String::valueOf)
            .toList();
        List<String> strongKeys = ((List<?>) scoreHints.get("strongKeys")).stream()
            .map(String::valueOf)
            .toList();

        assertThat(metadata).containsEntry("schemaVersion", AssetMetadataFactory.SCHEMA_VERSION);
        assertThat(asset.get("type")).isEqualTo("ssh_host");
        assertThat(executionContext.get("assetName")).isEqualTo("datanode-a");
        assertThat(executionContext.get("env")).isEqualTo("PROD");
        assertThat(labels).contains("cluster:hadoop-prod", "prod", "datanode");
        assertThat(labels).doesNotContain("hadoop", "docker");
        assertThat(scoreHints.get("environment")).isEqualTo("PROD");
        assertThat(strongKeys).contains("env", "cluster", "service");
        assertThat(metadata.toString()).doesNotContain("10.0.0.12");
    }

    @Test
    void gatewayMetadataContainsRoutingContractAndCandidates() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("order-db");
        datasource.setToolName("sql_order");
        datasource.setEnvironment("PROD");
        datasource.setJdbcUrl("jdbc:mysql://10.0.0.10/order");
        datasource.setRoutingLabelsJson("[\"orders\",\"databaseRole:primary\"]");

        Map<String, Object> asset = factory.sqlDatasource(datasource);
        Map<String, Object> gateway = factory.gateway(
            "sql_datasource",
            List.of(asset),
            List.of("datasourceId", "jdbcUrl")
        );
        Map<?, ?> routingContract = (Map<?, ?>) gateway.get("routingContract");
        Map<?, ?> selectionPolicy = (Map<?, ?>) routingContract.get("selectionPolicy");
        Map<?, ?> routingDecisionMode = (Map<?, ?>) routingContract.get("routingDecisionMode");
        Map<?, ?> weights = (Map<?, ?>) selectionPolicy.get("weights");
        List<String> forbiddenFields = ((List<?>) routingContract.get("forbiddenConcreteTargetFields")).stream()
            .map(String::valueOf)
            .toList();

        assertThat(gateway).containsEntry("schemaVersion", AssetMetadataFactory.SCHEMA_VERSION);
        assertThat(gateway).containsEntry("kind", "asset_selection");
        assertThat(gateway).containsEntry("routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION);
        assertThat((List<?>) gateway.get("assets")).hasSize(1);
        assertThat(routingContract.get("routingPolicyVersion")).isEqualTo(AssetMetadataFactory.ROUTING_POLICY_VERSION);
        assertThat(routingContract.get("routingTraceSchema")).isEqualTo(AssetMetadataFactory.ROUTING_TRACE_SCHEMA);
        assertThat(routingContract.get("onAmbiguity")).isEqualTo("ask_user");
        assertThat(selectionPolicy.get("type")).isEqualTo("rule_score");
        assertThat(selectionPolicy.get("tieBreaker")).isEqualTo("reject_as_ambiguous");
        assertThat(selectionPolicy.get("nearTieScoreDelta")).isEqualTo(0.05);
        assertThat(routingDecisionMode.get("clear")).isEqualTo("auto_select");
        assertThat(routingDecisionMode.get("near_tie")).isEqualTo("llm_rerank");
        assertThat(routingDecisionMode.get("true_ambiguity")).isEqualTo("ask_user");
        assertThat(weights.get("envMatch")).isEqualTo(0.50);
        assertThat(forbiddenFields).contains("datasourceId", "jdbcUrl");
        assertThat(gateway.toString()).doesNotContain("jdbc:mysql://10.0.0.10/order");
    }
}
