package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetDiscoveryServiceTest {

    @Test
    void queriesRedactedAssetsByLogicalContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig hadoopHost = sshHost("host-1", "dn-a", "PROD", "[\"cluster:hadoop-prod\",\"datanode\"]");
        SshHostConfig kafkaHost = sshHost("host-2", "broker-a", "PROD", "[\"cluster:kafka-prod\",\"broker\"]");
        when(hostService.listEnabled()).thenReturn(List.of(hadoopHost, kafkaHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of(
                "env", "prod",
                "cluster", "hadoop-prod",
                "targetType", "datanode"
            ),
            "trace", trace(),
            "limit", 10
        ));
        List<?> assets = (List<?>) result.get("assets");
        Map<?, ?> metadata = (Map<?, ?>) assets.get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");

        assertThat(result)
            .containsEntry("schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION)
            .containsEntry("success", true)
            .containsEntry("returnedCount", 1);
        assertThat(metadata.get("assetType")).isEqualTo("ssh_host");
        assertThat(asset.get("id")).isEqualTo("host-1");
        assertThat(result.toString()).doesNotContain("10.0.0.1", ".internal", "jdbc:", "https://");
    }

    @Test
    void returnsRedactedCandidateAssetsWithoutLogicalContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig dockerHost = sshHost("host-1", "ops-host", "DEV", null);
        dockerHost.setAllowedCommandsJson("[\"LIST_DOCKER_CONTAINERS\"]");
        when(hostService.listEnabled()).thenReturn(List.of(dockerHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of(),
            "trace", trace()
        ));

        assertThat(result)
            .containsEntry("returnedCount", 1)
            .containsEntry("broadDiscovery", true);
        assertThat((List<?>) result.get("assets")).hasSize(1);
        Map<?, ?> advice = (Map<?, ?>) result.get("broadDiscoveryAdvice");
        assertThat(advice.get("templateHint"))
            .isEqualTo("For execution-template questions, prefer assets whose capabilities.allowedCommandTemplates[].templateId, allowedCommandTemplateIds[], authorizedSqlTemplates[], or authorizedHttpTemplates[] contains a matching registered template id.");
        assertThat(result.toString()).contains("LIST_DOCKER_CONTAINERS");
        assertThat(result.toString()).doesNotContain("10.0.0.1");
    }

    @Test
    void usesRetrievalIntentToSelectSshAssetInsteadOfBroadFirstCandidate() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig scheduler = sshHost("host-1", "CDH\u8c03\u5ea6\u5668\u670d\u52a1\u5668", "DEV", null);
        SshHostConfig mysql = sshHost("host-2", "MySQL\u670d\u52a1\u5668", "DEV", "[\"mysql\"]");
        when(hostService.listEnabled()).thenReturn(List.of(scheduler, mysql));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of("intent", "\u5206\u6790 MySQL\u670d\u52a1\u5668 \u7ba1\u7406\u8fdb\u7a0b\u4fe1\u606f"),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result)
            .containsEntry("returnedCount", 1)
            .containsEntry("broadDiscovery", false);
        List<?> assets = (List<?>) result.get("assets");
        Map<?, ?> metadata = (Map<?, ?>) assets.get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        Map<?, ?> routingHints = (Map<?, ?>) metadata.get("routingHints");
        assertThat(asset.get("name")).isEqualTo("MySQL\u670d\u52a1\u5668");
        assertThat(((Map<?, ?>) result.get("filters")).get("intent"))
            .isEqualTo("\u5206\u6790 MySQL\u670d\u52a1\u5668 \u7ba1\u7406\u8fdb\u7a0b\u4fe1\u606f");
        assertThat(((Map<?, ?>) routingHints.get("assetQueryMatch")).get("strategy"))
            .isEqualTo("fuzzy_name_unique_candidate");
    }

    @Test
    void treatsLooseContextStringAsServiceFilter() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        when(hostService.listEnabled()).thenReturn(List.of(sshHost("host-1", "docker-host", "PROD", "[\"docker\"]")));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of(),
            "trace", trace(),
            "context", "docker"
        ));

        assertThat((List<?>) result.get("assets")).hasSize(1);
        assertThat(((Map<?, ?>) result.get("filters")).get("service")).isEqualTo("docker");
    }

    @Test
    void doesNotMatchPartialTokenFromCompoundAssetName() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig dockerHost = sshHost("host-1", "docker_service", "DEV", null);
        dockerHost.setTitle("docker_service_host");
        dockerHost.setDescription("Ubuntu 22.04 LTS host running docker containers");
        when(hostService.listEnabled()).thenReturn(List.of(dockerHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of("service", "docker"),
            "trace", trace()
        ));

        assertThat((List<?>) result.get("assets")).isEmpty();
        assertThat(result).containsEntry("returnedCount", 0);
        Map<?, ?> advice = (Map<?, ?>) result.get("emptyResultAdvice");
        assertThat(advice.get("reason")).isEqualTo("No enabled and published asset matched the exact logical filters.");
        assertThat(advice.get("doNotInvent"))
            .isEqualTo("Do not invent or transform service labels such as service:<topic> from the user's natural-language intent.");
        assertThat(result.toString()).doesNotContain("10.0.0.1");
    }

    @Test
    void matchesExactAssetNameFromCompoundAssetName() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig dockerHost = sshHost("host-1", "docker_service", "DEV", null);
        dockerHost.setTitle("docker_service_host");
        dockerHost.setDescription("Ubuntu 22.04 LTS host running docker containers");
        dockerHost.setAllowedCommandsJson("[\"CHECK_SYSTEM_OVERVIEW\"]");
        when(hostService.listEnabled()).thenReturn(List.of(dockerHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of("assetName", "docker_service"),
            "trace", trace()
        ));

        assertThat((List<?>) result.get("assets")).hasSize(1);
        assertThat(result).containsEntry("returnedCount", 1);
        List<?> assets = (List<?>) result.get("assets");
        Map<?, ?> metadata = (Map<?, ?>) assets.get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        Map<?, ?> capabilities = (Map<?, ?>) metadata.get("capabilities");
        assertThat(asset.get("name")).isEqualTo("docker_service");
        assertThat(asset.get("environment")).isEqualTo("DEV");
        assertThat(asset.get("toolName")).isEqualTo("ssh_docker_service");
        assertThat(capabilities.get("allowedCommandTemplateIds")).isEqualTo(List.of("CHECK_SYSTEM_OVERVIEW"));
        List<?> templates = (List<?>) capabilities.get("allowedCommandTemplates");
        assertThat(((Map<?, ?>) templates.get(0)).get("templateId")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(result).doesNotContainKey("data");
        assertThat(result.toString()).doesNotContain("10.0.0.1");
    }

    @Test
    void matchesAssetNameWhenUserAddsGenericDescriptorWords() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SqlDatasourceConfig mysql = datasource("ds-1", "MySQL248", "DEV", "[\"mysql\"]");
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(mysql));
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of("assetName", "MySQL248 database"),
            "trace", trace()
        ));

        assertThat((List<?>) result.get("assets")).hasSize(1);
        Map<?, ?> metadata = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        assertThat(asset.get("name")).isEqualTo("MySQL248");
    }

    @Test
    void returnsUniqueFuzzyAssetNameCandidateWhenPlannerConcatenatesDescription() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SqlDatasourceConfig mysql = datasource("ds-1", "本地MySQL测试服务", "DEV", "[\"mysql\"]");
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(mysql));
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of("assetName", "MySQL测试服务该服务应为MySQL数据库"),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        Map<?, ?> metadata = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        Map<?, ?> routingHints = (Map<?, ?>) metadata.get("routingHints");
        Map<?, ?> match = (Map<?, ?>) routingHints.get("assetQueryMatch");
        assertThat(asset.get("name")).isEqualTo("本地MySQL测试服务");
        assertThat(match.get("strategy")).isEqualTo("fuzzy_name_unique_candidate");
        assertThat(match.get("requestedAssetName")).isEqualTo("MySQL测试服务该服务应为MySQL数据库");
        assertThat(match.get("confidence")).isEqualTo("low");
    }

    @Test
    void returnsUnavailableAssetWhenExactMatchIsRegisteredButDisabled() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SqlDatasourceConfig mysql = datasource("ds-1", "MySQL248", "DEV", "[\"mysql\"]");
        mysql.setEnabled(false);
        when(hostService.listEnabled()).thenReturn(List.of());
        when(hostService.listAll()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listAll()).thenReturn(List.of(mysql));
        when(httpService.listEnabled()).thenReturn(List.of());
        when(httpService.listAll()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of("assetName", "MySQL248"),
            "trace", trace(),
            "limit", 10
        ));

        assertThat((List<?>) result.get("assets")).isEmpty();
        assertThat(result).containsEntry("returnedCount", 0).containsEntry("unavailableCount", 1);
        List<?> unavailableAssets = (List<?>) result.get("unavailableAssets");
        Map<?, ?> unavailable = (Map<?, ?>) unavailableAssets.get(0);
        Map<?, ?> asset = (Map<?, ?>) unavailable.get("asset");
        Map<?, ?> availability = (Map<?, ?>) unavailable.get("availability");
        assertThat(unavailable.get("kind")).isEqualTo("asset_unavailable");
        assertThat(asset.get("name")).isEqualTo("MySQL248");
        assertThat(availability.get("reason")).isEqualTo("registered_but_disabled_or_not_published");
        assertThat(((Map<?, ?>) result.get("emptyResultAdvice")).get("reason"))
            .isEqualTo("A registered asset matched the filters, but it is disabled or not published.");
    }

    @Test
    void rejectsConcreteTargetFields() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "filters", Map.of(
                "env", "prod",
                "jdbc_url", "jdbc:mysql://10.0.0.1/test"
            )
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Concrete target field is not allowed");
    }

    @Test
    void rejectsAssetQueryWithoutTargetKindOrAssetType() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "filters", Map.of("assetName", "MySQL248")
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("asset_query requires finalDecision or explicit targetKind");
    }

    @Test
    void rejectsDocumentTargetKindForAssetQuery() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "targetKind", "document",
            "confidence", 0.9,
            "filters", Map.of("intent", "read docs"),
            "trace", trace()
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("not allowed for asset_query");
    }

    @Test
    void rejectsAssetTypeConflictWithTargetKind() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "assetType", "ssh_host",
            "filters", Map.of("assetName", "MySQL248"),
            "trace", trace()
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("maps to assetType=sql_datasource");
    }

    @Test
    void respectsRequestedLimitWithoutTwentyCap() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        List<SshHostConfig> hosts = java.util.stream.IntStream.range(0, 25)
            .mapToObj(index -> sshHost("host-" + index, "host-" + index, "PROD", "[\"hadoop\"]"))
            .toList();
        when(hostService.listEnabled()).thenReturn(hosts);
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of("env", "prod", "service", "hadoop"),
            "trace", trace(),
            "limit", 100
        ));

        assertThat((List<?>) result.get("assets")).hasSize(25);
        assertThat(result)
            .containsEntry("limit", 100)
            .containsEntry("possiblyTruncated", false);
    }

    @Test
    void deduplicatesTableSearchHitsByDatabaseAssetId() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        LuceneMcpSearchService searchService = mock(LuceneMcpSearchService.class);
        SqlDatasourceConfig datasource = datasource("db-1", "customer-warehouse", "DEV", "[\"account\"]");
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(httpService.listEnabled()).thenReturn(List.of());
        when(searchService.enabled()).thenReturn(true);
        when(searchService.searchAssets(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(
                searchHit("db-1", "db-1:customer.account", 0.8F, "account"),
                searchHit("db-1", "db-1:customer.account_balance", 1.0F, "account_balance"),
                searchHit("db-1", "db-1:customer.account_trade", 0.9F, "account_trade")
            ));
        AssetDiscoveryService service = new AssetDiscoveryService(
            hostService,
            datasourceService,
            httpService,
            new AssetMetadataFactory(new ObjectMapper()),
            searchService,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.95,
            "filters", Map.of("intent", "account analysis"),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("returnedCount", 1).containsEntry("possiblyTruncated", false);
        assertThat((List<?>) result.get("assets")).hasSize(1);
        Map<?, ?> metadata = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        Map<?, ?> routingHints = (Map<?, ?>) metadata.get("routingHints");
        Map<?, ?> selection = (Map<?, ?>) routingHints.get("assetSelection");
        assertThat(selection.get("normalizedScore")).isEqualTo(1.0D);
    }

    @Test
    void resolvesExplicitAssetNameFromRegistryBeforeSharedMetadataIndex() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        LuceneMcpSearchService searchService = mock(LuceneMcpSearchService.class);
        SqlDatasourceConfig mysql = datasource("mysql-1", "248-test-database", "DEV", "[\"mysql\"]");
        SqlDatasourceConfig oracle = datasource("oracle-1", "risk-oracle-server", "DEV", "[\"oracle\"]");
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(mysql, oracle));
        when(httpService.listEnabled()).thenReturn(List.of());
        when(searchService.enabled()).thenReturn(true);
        AssetDiscoveryService service = new AssetDiscoveryService(
            hostService, datasourceService, httpService,
            new AssetMetadataFactory(new ObjectMapper()), searchService, new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.95,
            "filters", Map.of("assetname", "risk-oracle-server", "environment", "DEV", "database_type", "oracle"),
            "trace", trace(),
            "limit", 3
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat((Map<String, Object>) result.get("filters"))
            .containsEntry("assetName", "risk-oracle-server")
            .containsEntry("env", "DEV")
            .containsEntry("databaseType", "oracle");
        Map<?, ?> metadata = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        assertThat(((Map<?, ?>) metadata.get("asset")).get("name")).isEqualTo("risk-oracle-server");
        org.mockito.Mockito.verify(searchService, org.mockito.Mockito.never())
            .searchAssets(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void normalizesFilterAliasesAndDeduplicatesEquivalentRetrievalSignals() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        LuceneMcpSearchService searchService = mock(LuceneMcpSearchService.class);
        SqlDatasourceConfig oracle = datasource("oracle-1", "risk-oracle-server", "DEV", "[\"oracle\"]");
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(oracle));
        when(httpService.listEnabled()).thenReturn(List.of());
        when(searchService.enabled()).thenReturn(true);
        when(searchService.searchAssets(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit(
                "oracle-1", "asset", 1.0F, List.of("asset_registry"), "oracle-1",
                "asset_registry", "oracle-1", null, null, null, null, null
            )
        ));
        AssetDiscoveryService service = new AssetDiscoveryService(
            hostService, datasourceService, httpService,
            new AssetMetadataFactory(new ObjectMapper()), searchService, new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.95,
            "filters", Map.of(
                "environment", "DEV",
                "database_type", "oracle",
                "intent", "tablespace usage",
                "goal", "tablespace usage",
                "queryterms", List.of("tablespace usage")
            ),
            "trace", trace(),
            "limit", 3
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        org.mockito.Mockito.verify(searchService).searchAssets(org.mockito.ArgumentMatchers.argThat(request ->
            "tablespace usage".equals(request.queryText())
                && "oracle".equals(request.dbType())
                && "DEV".equals(request.env())
        ));
    }

    @Test
    void boundDatabaseAssetIsExclusiveAndNeverActsAsFallback() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        when(hostService.listEnabled()).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(
            datasource("ds-tdh", "TDH数据仓库", "DEV", null),
            datasource("ds-248", "248测试数据库", "DEV", null)
        ));
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.95,
            "filters", Map.of("assetName", "248测试数据库"),
            "defaultDataAsset", Map.of("assetName", "TDH数据仓库", "assetType", "DATABASE", "enabled", true),
            "assetSelectionPolicy", Map.of("strategy", "SEARCH_FIRST_DEFAULT_FALLBACK", "fallbackWhenEmpty", true),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        Map<?, ?> metadata = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        assertThat(asset.get("name")).isEqualTo("TDH数据仓库");
        Map<?, ?> routingHints = (Map<?, ?>) metadata.get("routingHints");
        Map<?, ?> selection = (Map<?, ?>) routingHints.get("assetSelection");
        assertThat(selection.get("strategy")).isEqualTo("BOUND_ASSET_ONLY");
        assertThat(selection.get("fallbackTriggered")).isEqualTo(false);
    }

    private AssetDiscoveryService service(SshHostConfigService hostService,
                                          SqlDatasourceConfigService datasourceService,
                                          HttpEndpointConfigService httpService) {
        return new AssetDiscoveryService(
            hostService,
            datasourceService,
            httpService,
            new AssetMetadataFactory(new ObjectMapper())
        );
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }

    private LuceneMcpSearchService.SearchHit searchHit(String assetId, String documentId, float score, String table) {
        return new LuceneMcpSearchService.SearchHit(
            assetId, "asset", score, List.of("opensearch_bm25:" + score), documentId,
            "metadata_table", assetId, "customer", table, "customer." + table, null, null
        );
    }

    private SshHostConfig sshHost(String id, String name, String env, String routingLabelsJson) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName(name);
        host.setTitle(name);
        host.setToolName("ssh_" + name);
        host.setHostname("10.0.0.1");
        host.setEnvironment(env);
        host.setRoutingLabelsJson(routingLabelsJson);
        host.setCapabilitiesJson("[\"ssh\",\"linux_command_execute\"]");
        host.setEnabled(true);
        return host;
    }

    @SuppressWarnings("unused")
    private SqlDatasourceConfig datasource(String id, String name, String env, String labels) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setTitle(name);
        datasource.setToolName("sql_" + name);
        datasource.setJdbcUrl("jdbc:mysql://10.0.0.10/orders");
        datasource.setEnvironment(env);
        datasource.setRoutingLabelsJson(labels);
        datasource.setEnabled(true);
        return datasource;
    }

    @SuppressWarnings("unused")
    private HttpEndpointConfig endpoint(String id, String name, String env, String labels) {
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        endpoint.setId(id);
        endpoint.setName(name);
        endpoint.setTitle(name);
        endpoint.setToolName("http_" + name);
        endpoint.setUrlTemplate("https://example.com/" + name);
        endpoint.setEnvironment(env);
        endpoint.setRoutingLabelsJson(labels);
        endpoint.setEnabled(true);
        return endpoint;
    }
}
