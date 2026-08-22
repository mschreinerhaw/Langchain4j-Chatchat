package com.chatchat.chat.skills;

import com.chatchat.chat.contract.ContractRuleRecordCodec;
import com.chatchat.chat.contract.RuntimeContractRuleSchemaMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCatalogServiceTest {

    @Test
    void persistsAndReadsBackRuntimeEnvironment() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition saved = service.upsert(draftWithEnvironment("dev"));

        ArgumentCaptor<SkillConfigEntity> entityCaptor = ArgumentCaptor.forClass(SkillConfigEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getWorkflowConfigJson())
            .contains("\"runtimeEnvironment\":\"DEV\"");
        assertThat(saved.workflowConfig()).containsEntry("runtimeEnvironment", "DEV");
    }

    @Test
    void persistsMetadataDrivenStrictDocumentScopeMode() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of(
            "enabled", true, "document_scope_mode", "STRICT")));

        assertThat(saved.workflowConfig()).containsEntry("documentScopeMode", "strict");
    }

    @Test
    void persistsRequiredToolParametersAsGenericRuntimeContract() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of(
            "enabled", true,
            "required_tool_parameters", Map.of("search_tool", Map.of("strict_mode", true))
        )));

        ArgumentCaptor<SkillConfigEntity> entityCaptor = ArgumentCaptor.forClass(SkillConfigEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getWorkflowConfigJson())
            .contains("\"requiredToolParameters\"")
            .contains("\"strict_mode\":true");
        assertThat(saved.workflowConfig()).containsKey("requiredToolParameters");
    }

    @Test
    void rejectsUnknownRuntimeEnvironment() {
        SkillCatalogService service = new SkillCatalogService(
            mock(SkillConfigRepository.class),
            mock(SkillConfigVersionRepository.class),
            new ObjectMapper(),
            mock(JdbcTemplate.class),
            summaryContractService());

        assertThatThrownBy(() -> service.upsert(draftWithEnvironment("sandbox")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DEV, TEST, UAT, PROD");
    }

    @Test
    void normalizesAndPersistsAgentBudgetCeilings() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of(
            "enabled", true,
            "executionStrategy", Map.of(
                "max_steps", 6,
                "cost_budget", 12.5,
                "latency_budget_ms", 180000
            )
        )));

        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) saved.workflowConfig().get("executionStrategy");
        assertThat(strategy)
            .containsEntry("maxSteps", 6)
            .containsEntry("costBudget", 12.5)
            .containsEntry("latencyBudgetMs", 180000)
            .doesNotContainKeys("max_steps", "cost_budget", "latency_budget_ms");
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsLockedSummarizeAvailablePolicyByDefault() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of()));

        ArgumentCaptor<SkillConfigEntity> entityCaptor = ArgumentCaptor.forClass(SkillConfigEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getWorkflowConfigJson())
            .contains("\"resultHandlingPolicy\"")
            .contains("\"mode\":\"SUMMARIZE_AVAILABLE\"")
            .contains("\"overrideAllowed\":false");
        Map<String, Object> policy = (Map<String, Object>) saved.workflowConfig()
            .get(SkillCatalogService.RESULT_HANDLING_POLICY);
        assertThat(policy)
            .containsEntry("mode", "SUMMARIZE_AVAILABLE")
            .containsEntry("continueOnPartialSuccess", true)
            .containsEntry("summarizeSuccessfulResults", true)
            .containsEntry("includeFailedResultReasons", true)
            .containsEntry("failRunWhenAnyChildFails", false)
            .containsEntry("overrideAllowed", false);
        Map<String, Object> recordPolicy = (Map<String, Object>) policy.get("recordAnalysisPolicy");
        assertThat(recordPolicy)
            .containsEntry("contractVersion", "record_grounded_analysis.v1")
            .containsEntry("requireRecordGroundedAnalysis", true)
            .containsEntry("requireCompleteRecordCoverage", true)
            .containsEntry("iterativeSummarizationWhenOversized", true)
            .containsEntry("allowExecutionMetadataOnlyAnswer", false)
            .containsEntry("completionCondition", "PROCESSED_RECORD_COUNT_EQUALS_RETURNED_RECORD_COUNT")
            .containsEntry("immutable", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresResultPolicyMutationUnlessOverrideIsExplicitlyEnabled() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());

        SkillDefinition locked = service.upsert(draftWithWorkflow(Map.of(
            "resultHandlingPolicy", Map.of(
                "mode", "STRICT_BATCH_SUCCESS",
                "failRunWhenAnyChildFails", true
            )
        )));
        Map<String, Object> lockedPolicy = (Map<String, Object>) locked.workflowConfig()
            .get(SkillCatalogService.RESULT_HANDLING_POLICY);
        assertThat(lockedPolicy)
            .containsEntry("mode", "SUMMARIZE_AVAILABLE")
            .containsEntry("failRunWhenAnyChildFails", false)
            .containsEntry("overrideAllowed", false);

        SkillDefinition overridden = service.upsert(draftWithWorkflow(Map.of(
            "resultHandlingPolicy", Map.of(
                "overrideAllowed", true,
                "mode", "STRICT_BATCH_SUCCESS",
                "continueOnPartialSuccess", false,
                "failRunWhenAnyChildFails", true
            )
        )));
        Map<String, Object> overridePolicy = (Map<String, Object>) overridden.workflowConfig()
            .get(SkillCatalogService.RESULT_HANDLING_POLICY);
        assertThat(overridePolicy)
            .containsEntry("mode", "STRICT_BATCH_SUCCESS")
            .containsEntry("continueOnPartialSuccess", false)
            .containsEntry("failRunWhenAnyChildFails", true)
            .containsEntry("overrideAllowed", true);
        Map<String, Object> recordPolicy = (Map<String, Object>) overridePolicy.get("recordAnalysisPolicy");
        assertThat(recordPolicy)
            .containsEntry("requireRecordGroundedAnalysis", true)
            .containsEntry("requireCompleteRecordCoverage", true)
            .containsEntry("iterativeSummarizationWhenOversized", true)
            .containsEntry("allowExecutionMetadataOnlyAnswer", false)
            .containsEntry("immutable", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startupBackfillPreservesUnknownWorkflowFields() throws Exception {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId("existing_agent");
        entity.setWorkflowConfigJson("{\"customExtension\":{\"keep\":\"value\"},\"enabled\":true}");
        when(repository.findAll()).thenReturn(List.of(entity));
        SkillCatalogService service = new SkillCatalogService(
            repository, mock(SkillConfigVersionRepository.class), new ObjectMapper(), mock(JdbcTemplate.class),
            summaryContractService());

        service.ensureResultHandlingPolicyPersisted();

        verify(repository).saveAll(List.of(entity));
        Map<String, Object> persisted = new ObjectMapper().readValue(entity.getWorkflowConfigJson(), Map.class);
        assertThat((Map<String, Object>) persisted.get("customExtension"))
            .containsEntry("keep", "value");
        assertThat(persisted).containsEntry("enabled", true);
        assertThat((Map<String, Object>) persisted.get(SkillCatalogService.RESULT_HANDLING_POLICY))
            .containsEntry("mode", "SUMMARIZE_AVAILABLE")
            .containsEntry("overrideAllowed", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void migratesLegacySqlScriptBindingToUnifiedQueryGateway() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class), summaryContractService());
        String queryTool = "mcp_chatchat_mcp_server_sql_query_execute";
        String scriptTool = "mcp_chatchat_mcp_server_sql_script_execute";
        SkillDefinition draft = new SkillDefinition(
            "db_ops_assistant", "Database operations", null, List.of(), List.of(), "agent_chat",
            null, null, null, List.of(), List.of(), List.of(queryTool, scriptTool),
            List.of(), List.of(),
            List.of(
                new SkillToolConfig(queryTool, "SQL query", "mcp", null, List.of(), "read", 5, true),
                new SkillToolConfig(scriptTool, "SQL script", "mcp", null, List.of(), "read", 5, true)
            ),
            null,
            Map.of("steps", List.of(
                Map.of("step", 1, "tool", "asset_search", "required", true),
                Map.of("step", 2, "tool", queryTool, "required", true,
                    "dependsOn", List.of("asset_search")),
                Map.of("step", 3, "tool", scriptTool, "required", true,
                    "dependsOn", List.of(queryTool),
                    "templateId", "risk_sql_script_execute")
            )),
            null, null, List.of(), SkillCatalogService.MARKET_STATUS_DRAFT, false
        );

        SkillDefinition saved = service.upsert(draft);

        assertThat(saved.boundMcpToolNames()).containsExactly(queryTool);
        assertThat(saved.toolConfigs()).extracting(SkillToolConfig::toolName).containsExactly(queryTool);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) saved.workflowConfig().get("steps");
        assertThat(steps).extracting(step -> step.get("tool"))
            .containsExactly("asset_search", queryTool);
        assertThat(steps.get(1).get("dependsOn")).isEqualTo(List.of("asset_search"));
        assertThat(steps.get(1).get("templateId")).isEqualTo("risk_sql_script_execute");
        assertThat(saved.workflowConfig().toString())
            .doesNotContain("mcp_chatchat_mcp_server_sql_script_execute");
    }

    private SkillDefinition draftWithEnvironment(String environment) {
        return draftWithWorkflow(Map.of("enabled", true, "runtimeEnvironment", environment));
    }

    private SummaryContractService summaryContractService() {
        SummaryContractRepository repository = mock(SummaryContractRepository.class);
        when(repository.findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(
            SummaryContractService.RECORD_ANALYSIS_CONTRACT_KEY)).thenReturn(List.of());
        when(repository.saveAndFlush(any(SummaryContractEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        return new SummaryContractService(
            repository, new ObjectMapper(), new ContractRuleRecordCodec(),
            mock(RuntimeContractRuleSchemaMigrator.class)
        );
    }

    private SkillDefinition draftWithWorkflow(Map<String, Object> workflowConfig) {
        return new SkillDefinition(
            "db_ops_assistant",
            "数据库运维助手",
            null,
            List.of(),
            List.of(),
            "agent_chat",
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            workflowConfig,
            null,
            null,
            List.of(),
            SkillCatalogService.MARKET_STATUS_DRAFT,
            false
        );
    }
}
