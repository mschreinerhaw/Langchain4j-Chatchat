package com.chatchat.agents.runtime;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRuntimeServiceTest {

    @Test
    void agentFinancialPolicyForcesWebSearchParameterAndAuditMetadata() {
        String toolName = "mcp_dynamic_service_web_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title("Dynamic web search").categories(List.of("mcp")).build());
        AtomicReference<ToolInput> capturedInput = new AtomicReference<>();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            capturedInput.set(invocation.getArgument(1));
            return ToolOutput.success(Map.of("results", List.of()));
        });
        ToolRuntimeService service = new ToolRuntimeService(
            registry, new ObjectMapper(), properties(), List.of(), List.of());
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName(toolName).runtimeMode("agent_chat").requestId("forced-financial-1")
                .conversationId("conversation-1").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of(toolName))
                .attributes(Map.of(
                    "forceStructuredFinancialData", true,
                    "financialIntentQuery", "full user market question"))
                .toolInput(ToolInput.builder().parameters(Map.of(
                    "query", "latest market",
                    "financial_data_required", false
                )).build())
                .build());

            assertThat(capturedInput.get().getParameters())
                .containsEntry("financial_data_required", true);
            assertThat(capturedInput.get().getContext())
                .containsEntry("financialDataPolicy", "FORCED")
                .containsEntry("financialDataModelRequired", false)
                .containsEntry("financialDataEffectiveRequired", true)
                .containsEntry("financialIntentQuery", "full user market question");
            assertThat(execution.audit())
                .containsEntry("financialDataPolicy", "FORCED")
                .containsEntry("financialDataEffectiveRequired", true);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void dedicatedFinancialToolKeepsWebFinancialFallbackSoExecutionFailureCannotLoseOriginalEffect() {
        String webTool = "mcp_dynamic_service_web_search";
        String financialTool = "mcp_dynamic_service_financial_data_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(webTool)).thenReturn(ToolMetadata.builder()
            .id(webTool).title("Dynamic web search").categories(List.of("mcp")).build());
        AtomicReference<ToolInput> capturedInput = new AtomicReference<>();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            capturedInput.set(invocation.getArgument(1));
            return ToolOutput.success(Map.of("results", List.of()));
        });
        ToolRuntimeService service = new ToolRuntimeService(
            registry, new ObjectMapper(), properties(), List.of(), List.of());
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName(webTool).runtimeMode("agent_chat").requestId("delegated-financial-1")
                .conversationId("conversation-1").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of(webTool, financialTool))
                .attributes(Map.of(
                    "forceStructuredFinancialData", true,
                    "dedicatedFinancialDataTool", financialTool))
                .toolInput(ToolInput.builder().parameters(Map.of(
                    "query", "latest securities news",
                    "financial_data_required", false
                )).build())
                .build());

            assertThat(capturedInput.get().getParameters())
                .containsEntry("financial_data_required", true);
            assertThat(capturedInput.get().getContext())
                .containsEntry("financialDataPolicy", "FORCED_WITH_DEDICATED_TOOL")
                .containsEntry("financialDataEffectiveRequired", true)
                .containsEntry("dedicatedFinancialDataTool", financialTool);
            assertThat(execution.audit())
                .containsEntry("financialDataPolicy", "FORCED_WITH_DEDICATED_TOOL")
                .containsEntry("financialDataEffectiveRequired", true);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void oversizedToolOutputIsExternalizedAndOnlyBoundedReferenceCrossesRuntimeBoundary() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("large_tool")).thenReturn(ToolMetadata.builder()
            .id("large_tool").title("Large Tool").build());
        when(registry.executeEnhancedTool(any(), any()))
            .thenReturn(ToolOutput.success(Map.of("rows", "x".repeat(100_000))));
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setMaxOutputBytes(16_384);
        runtimeProperties.setMaxOutputPreviewChars(2_000);
        ToolRuntimeService service = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        AtomicReference<String> stored = new AtomicReference<>();
        service.setEvidenceStore(new AgentEvidenceStore() {
            @Override public boolean isEnabled() { return true; }
            @Override public void put(String documentId, String tenantId, String runId,
                                      String evidenceId, String json) { stored.set(json); }
            @Override public java.util.Optional<String> get(String documentId) {
                return java.util.Optional.ofNullable(stored.get());
            }
            @Override public void delete(String documentId) { stored.set(null); }
        });
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName("large_tool").runtimeMode("agent_chat").requestId("large-output-1")
                .conversationId("conversation-1").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of("large_tool"))
                .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build()).build());

            assertThat(stored.get()).hasSizeGreaterThan(90_000);
            assertThat(execution.output().getData()).isInstanceOfSatisfying(Map.class, reference ->
                assertThat(reference).containsEntry("outputExternal", true)
                    .containsEntry("outputTruncated", true)
                    .containsKeys("documentId", "evidenceId", "preview"));
            assertThat(execution.trace().getOutput()).hasSizeLessThan(5_000);
            assertThat(execution.output().getMetadata())
                .containsEntry("outputTruncated", true)
                .containsKeys("outputDocumentId", "outputEvidenceId", "outputOriginalBytes");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void oversizedAssetDiscoveryPreservesRedactedRoutingProjection() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String toolName = "tenant_registered_asset_discovery";
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title("Asset discovery").build());
        Map<String, Object> asset = Map.of(
            "id", "asset-17",
            "name", "docker-database-simulator",
            "displayName", "Docker 数据库模拟服务器",
            "environment", "DEV",
            "toolName", "ssh_container_test_service",
            "description", "x".repeat(100_000)
        );
        when(registry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success(Map.of(
            "schemaVersion", "asset_query_result.v1",
            "assets", List.of(Map.of("asset", asset))
        )));
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setMaxOutputBytes(16_384);
        ToolRuntimeService service = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName(toolName).runtimeMode("agent_chat").requestId("asset-output-1")
                .conversationId("conversation-1").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of(toolName))
                .toolInput(ToolInput.builder().parameters(Map.of()).build()).build());

            assertThat(execution.output().getData()).isInstanceOfSatisfying(Map.class, reference -> {
                assertThat(reference).containsEntry("outputTruncated", true)
                    .containsKey("routingProjection");
                Map<?, ?> projection = (Map<?, ?>) reference.get("routingProjection");
                List<?> assets = (List<?>) projection.get("assets");
                Map<?, ?> projected = (Map<?, ?>) ((Map<?, ?>) assets.get(0)).get("asset");
                assertThat(projected.get("id")).isEqualTo("asset-17");
                assertThat(projected.get("name")).isEqualTo("docker-database-simulator");
                assertThat(projected.get("environment")).isEqualTo("DEV");
                assertThat(projected.containsKey("description")).isFalse();
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void oversizedTemplateDiscoveryPreservesExecutableContractProjection() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        String toolName = "tenant_database_query_template_query";
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title("Template discovery").build());
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", "sample_margin_trade_latest");
        template.put("name", "Latest margin trading observations");
        template.put("description", "x".repeat(100_000));
        template.put("parameterSchema", Map.of(
            "type", "object", "properties", Map.of(), "required", List.of()));
        template.put("sqlExecutionBinding", Map.of(
            "toolName", "sql_query_execute",
            "templateId", "sample_margin_trade_latest",
            "executionContext", Map.of(
                "assetName", "financial-market-runtime", "env", "RUNTIME")));
        template.put("templateDsl", Map.of("sql", "select secret implementation body"));
        when(registry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success(Map.of(
            "schemaVersion", "template_query_result.v3",
            "queryIr", Map.of("asset", Map.of("selected", Map.of(
                "id", "host-41", "name", "tenant-runtime-host", "environment", "DEV",
                "password", "must-not-leak"))),
            "templates", List.of(template)
        )));
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setMaxOutputBytes(16_384);
        ToolRuntimeService service = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName(toolName).runtimeMode("agent_chat").requestId("template-output-1")
                .conversationId("conversation-1").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of(toolName))
                .toolInput(ToolInput.builder().parameters(Map.of()).build()).build());

            assertThat(execution.output().getData()).isInstanceOfSatisfying(Map.class, reference -> {
                Map<?, ?> projection = (Map<?, ?>) reference.get("routingProjection");
                List<?> templates = (List<?>) projection.get("templates");
                Map<?, ?> projected = (Map<?, ?>) templates.get(0);
                assertThat(projected.get("templateId")).isEqualTo("sample_margin_trade_latest");
                assertThat(projected.containsKey("parameterSchema")).isTrue();
                assertThat(projected.containsKey("sqlExecutionBinding")).isTrue();
                assertThat(projected.get("description").toString()).hasSizeLessThan(2_100);
                assertThat(projected.containsKey("templateDsl")).isFalse();
                assertThat(projected.containsKey("sql")).isFalse();
                Map<?, ?> selected = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) projection
                    .get("queryIr")).get("asset")).get("selected");
                assertThat(selected.get("id")).isEqualTo("host-41");
                assertThat(selected.get("name")).isEqualTo("tenant-runtime-host");
                assertThat(selected.get("environment")).isEqualTo("DEV");
                assertThat(selected.containsKey("password")).isFalse();
            });
            Map<?, ?> traceOutput = new ObjectMapper().readValue(execution.trace().getOutput(), Map.class);
            assertThat(traceOutput.get("routingProjection")).isInstanceOfSatisfying(Map.class, projection -> {
                List<?> templates = (List<?>) projection.get("templates");
                assertThat(templates).singleElement().isInstanceOfSatisfying(Map.class, projected ->
                    assertThat(projected)
                        .containsEntry("templateId", "sample_margin_trade_latest")
                        .containsKey("sqlExecutionBinding")
                        .doesNotContainKeys("templateDsl", "sql"));
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void blockedAuditPersistenceCannotBlockToolResponse() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("fast_tool")).thenReturn(ToolMetadata.builder()
            .id("fast_tool").title("Fast Tool").build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        CountDownLatch auditStarted = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        ToolRuntimeProperties properties = properties();
        properties.setAuditSinkTimeoutMs(100);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties, new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of(), List.of(record -> {
                auditStarted.countDown();
                try {
                    releaseAudit.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a database driver that does not promptly honor cancellation.
                    try {
                        releaseAudit.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException second) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        long started = System.nanoTime();
        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName("fast_tool").runtimeMode("agent_chat").requestId("audit-timeout")
                .userId("user-1").allowedTools(List.of("fast_tool"))
                .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build()).build());

            assertThat(execution.output().isSuccess()).isTrue();
            assertThat(auditStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);
        } finally {
            releaseAudit.countDown();
            service.shutdown();
        }
    }

    @Test
    void diagnosticAndToolTimeoutDefaultsAreThirtyMinutes() {
        assertThat(AgentRunRequest.DEFAULT_TIMEOUT_MS).isEqualTo(1_800_000L);
        assertThat(new ToolRuntimeProperties().safeDefaultToolTimeoutMs()).isEqualTo(1_800_000L);
    }

    @Test
    void defaultsToThreeToolRetries() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("unstable_tool")).thenReturn(ToolMetadata.builder()
            .id("unstable_tool")
            .title("Unstable Tool")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.failure("temporary failure"));
        ToolRuntimeProperties retryProperties = new ToolRuntimeProperties();
        retryProperties.setCircuitBreakerFailureThreshold(10);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            retryProperties,
            List.of(),
            List.of()
        );

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("unstable_tool")
            .runtimeMode("agent_chat")
            .requestId("req-default-tool-retry")
            .conversationId("conv-default-tool-retry")
            .userId("user-1")
            .allowedTools(List.of("unstable_tool"))
            .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.output().getMetadata())
            .containsEntry("toolRetryAttempts", 3)
            .containsEntry("toolCallAttempt", 4)
            .containsEntry("toolCallMaxAttempts", 4);
        verify(toolRegistry, times(4)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowPageConfigurationControlsToolRetries() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("recovering_tool")).thenReturn(ToolMetadata.builder()
            .id("recovering_tool")
            .title("Recovering Tool")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.failure("temporary failure"),
            ToolOutput.success("recovered")
        );
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            new ToolRuntimeProperties(),
            List.of(),
            List.of()
        );

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("recovering_tool")
            .runtimeMode("agent_chat")
            .requestId("req-configured-tool-retry")
            .conversationId("conv-configured-tool-retry")
            .userId("user-1")
            .allowedTools(List.of("recovering_tool"))
            .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build())
            .attributes(Map.of(
                "mcpWorkflow", Map.of(
                    "enabled", true,
                    "workflow", "retry_workflow",
                    "executionStrategy", Map.of(
                        "mode", "sequential",
                        "stopOnError", true,
                        "toolRetryAttempts", 1
                    ),
                    "steps", List.of(
                        Map.of("step", 1, "tool", "recovering_tool", "required", true)
                    )
                )
            ))
            .build());

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(execution.output().getDataAsString()).isEqualTo("recovered");
        assertThat(execution.output().getMetadata())
            .containsEntry("toolRetryAttempts", 1)
            .containsEntry("toolCallAttempt", 2)
            .containsEntry("toolCallMaxAttempts", 2);
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void deniesToolOutsideAllowedPolicy() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("sql_query")).thenReturn(ToolMetadata.builder()
            .id("sql_query")
            .title("SQL Query")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("sql_query")
            .runtimeMode("agent_chat")
            .requestId("req-1")
            .conversationId("conv-1")
            .userId("user-1")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of("sql", "select 1")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.output().getErrorMessage()).contains("not allowed");
        assertThat(execution.trace().getRuntimeMetadata()).containsEntry("outcome", "denied");
        assertThat(service.snapshot().deniedCalls()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachesUnifiedGovernanceToReadonlyToolTraceAndAudit() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("db_read")).thenReturn(ToolMetadata.builder()
            .id("db_read")
            .title("DB Read")
            .riskLevel("low")
            .operationType("read")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("rows"));
        List<ToolRuntimeAuditRecord> audits = new ArrayList<>();
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            List.of(),
            List.of(audits::add)
        );

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("db_read")
            .runtimeMode("agent_chat")
            .requestId("req-governance-read")
            .conversationId("conv-governance-read")
            .tenantId("tenant-a")
            .userId("user-a")
            .allowedTools(List.of("db_read"))
            .toolInput(ToolInput.builder().userId("user-a").parameters(Map.of("sql", "select 1")).build())
            .attributes(Map.of("roles", List.of("analyst"), "auditId", "audit-read-1"))
            .build());

        Map<String, Object> governance = (Map<String, Object>) execution.audit().get("governance");
        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(governance)
            .containsEntry("contractVersion", "tool_governance_v1")
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("userId", "user-a")
            .containsEntry("riskLevel", "readonly")
            .containsEntry("policyDecision", "ALLOW")
            .containsEntry("confirmRequired", false)
            .containsEntry("auditId", "audit-read-1");
        assertThat((List<String>) governance.get("roles")).containsExactly("analyst");
        assertThat(execution.trace().getRuntimeMetadata().get("governance")).isEqualTo(governance);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).runtimeMetadata().get("governance")).isEqualTo(governance);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresConfirmationForNonMcpWriteToolThroughUnifiedGovernance() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("http_post")).thenReturn(ToolMetadata.builder()
            .id("http_post")
            .title("HTTP POST")
            .riskLevel("low")
            .operationType("write")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("http_post")
            .runtimeMode("agent_chat")
            .requestId("req-governance-write")
            .conversationId("conv-governance-write")
            .tenantId("tenant-a")
            .userId("user-a")
            .allowedTools(List.of("http_post"))
            .toolInput(ToolInput.builder().userId("user-a").parameters(Map.of("url", "https://example.com", "body", "{}")).build())
            .build());

        Map<String, Object> governance = (Map<String, Object>) execution.audit().get("governance");
        assertThat(execution.outcome()).isEqualTo("confirmation_required");
        assertThat(governance)
            .containsEntry("contractVersion", "tool_governance_v1")
            .containsEntry("riskLevel", "confirm_required")
            .containsEntry("policyDecision", "REQUIRE_CONFIRM")
            .containsEntry("confirmRequired", true)
            .containsEntry("confirmed", false);
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void rateLimitRejectsSecondCallWithinWindow() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .isRateLimited(true)
            .maxCallsPerMinute(1)
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("web_search")
            .runtimeMode("tool_direct")
            .requestId("req-2")
            .conversationId("conv-2")
            .userId("user-2")
            .allowedTools(List.of("web_search"))
            .toolInput(ToolInput.builder().userId("user-2").parameters(Map.of("query", "weather")).build())
            .build();

        ToolRuntimeExecution first = service.execute(request);
        ToolRuntimeExecution second = service.execute(request);

        assertThat(first.output().isSuccess()).isTrue();
        assertThat(second.output().isSuccess()).isFalse();
        assertThat(second.trace().getRuntimeMetadata()).containsEntry("outcome", "rate_limited");
        assertThat(service.snapshot().rateLimitedCalls()).isEqualTo(1);
        verify(toolRegistry, times(1)).executeEnhancedTool(any(), any());
    }

    @Test
    void timesOutLongRunningToolExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("slow_tool")).thenReturn(ToolMetadata.builder()
            .id("slow_tool")
            .title("Slow Tool")
            .timeoutMillis(50L)
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(250);
            return ToolOutput.success("late");
        });
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName("slow_tool")
                .runtimeMode("agent_chat")
                .requestId("req-timeout-tool")
                .conversationId("conv-timeout-tool")
                .tenantId("tenant-1")
                .userId("user-timeout")
                .allowedTools(List.of("slow_tool"))
                .toolInput(ToolInput.builder().userId("user-timeout").parameters(Map.of()).build())
                .build());

            assertThat(execution.output().isSuccess()).isFalse();
            assertThat(execution.output().getExceptionType()).isEqualTo("TOOL_TIMEOUT");
            assertThat(execution.output().getErrorMessage()).contains("timed out");
            assertThat(execution.output().getMetadata()).containsEntry("retryable", false);
            assertThat(execution.outcome()).isEqualTo("failed");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void mcpToolExecutionHonorsConfiguredTimeout() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_web_search")).thenReturn(ToolMetadata.builder()
            .id("mcp_chatchat_mcp_server_web_search")
            .title("MCP Web Search")
            .categories(List.of("mcp"))
            .timeoutMillis(10L)
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(80);
            return ToolOutput.success("search result");
        });
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setDefaultRetryAttempts(0);
        runtimeProperties.setCircuitBreakerFailureThreshold(10);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), runtimeProperties, List.of(), List.of());

        try {
            ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
                .toolName("mcp_chatchat_mcp_server_web_search")
                .runtimeMode("agent_chat")
                .requestId("req-mcp-no-timeout")
                .conversationId("conv-mcp-no-timeout")
                .tenantId("tenant-1")
                .userId("user-mcp")
                .allowedTools(List.of("mcp_chatchat_mcp_server_web_search"))
                .toolInput(ToolInput.builder().userId("user-mcp").parameters(Map.of("query", "market")).build())
                .build());

            assertThat(execution.output().isSuccess()).isFalse();
            assertThat(execution.output().getExceptionType()).isEqualTo("TOOL_TIMEOUT");
            assertThat(execution.output().getErrorMessage()).contains("timed out");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void opensCircuitAfterRepeatedFailures() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_finance_quotes")).thenReturn(ToolMetadata.builder()
            .id("mcp_finance_quotes")
            .title("Finance Quotes")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.failure("boom-1"),
            ToolOutput.failure("boom-2")
        );
        ToolRuntimeProperties properties = properties();
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setCircuitBreakerOpenSeconds(60);
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties, List.of(), List.of());

        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("mcp_finance_quotes")
            .runtimeMode("agent_chat")
            .requestId("req-3")
            .conversationId("conv-3")
            .userId("user-3")
            .allowedTools(List.of("mcp_finance_quotes"))
            .toolInput(ToolInput.builder().userId("user-3").parameters(Map.of("query", "AAPL")).build())
            .build();

        ToolRuntimeExecution first = service.execute(request);
        ToolRuntimeExecution second = service.execute(request);
        ToolRuntimeExecution third = service.execute(request);

        assertThat(first.output().isSuccess()).isFalse();
        assertThat(second.output().isSuccess()).isFalse();
        assertThat(third.output().isSuccess()).isFalse();
        assertThat(third.trace().getRuntimeMetadata()).containsEntry("outcome", "circuit_open");
        assertThat(service.snapshot().openCircuits()).isEqualTo(1);
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void requiresConfirmationBeforeMediumRiskMcpToolExecutes() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_customer_asset")).thenReturn(ToolMetadata.builder()
            .id("mcp_customer_asset")
            .title("Customer Asset")
            .riskLevel("medium")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("mcp_customer_asset")
            .runtimeMode("agent_chat")
            .requestId("req-4")
            .conversationId("conv-4")
            .tenantId("tenant-1")
            .userId("user-4")
            .allowedTools(List.of("mcp_customer_asset"))
            .toolInput(ToolInput.builder().userId("user-4").parameters(Map.of("customer_id", "c-001")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("confirmation_required");
        assertThat(execution.audit()).containsEntry("policyResult", "ask_before_execute");
        assertThat(execution.audit()).containsKey("confirmation");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void runtimeLevelForbiddenDeniesToolBeforeExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("server_restart")).thenReturn(ToolMetadata.builder()
            .id("server_restart")
            .title("Server Restart")
            .runtimeLevel("forbidden")
            .riskLevel("high")
            .operationType("write")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("server_restart")
            .runtimeMode("agent_chat")
            .requestId("req-forbidden")
            .conversationId("conv-forbidden")
            .tenantId("tenant-ops")
            .userId("ops-user")
            .allowedTools(List.of("server_restart"))
            .toolInput(ToolInput.builder().userId("ops-user").parameters(Map.of("service", "nginx")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("denied");
        assertThat(execution.audit()).containsEntry("runtimeLevel", "forbidden");
        assertThat(execution.audit().get("matchedPolicyRules").toString()).contains("runtime_level.forbidden=deny");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void requestRuntimeLevelCanRequireConfirmationForReadonlyTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("log_search")).thenReturn(ToolMetadata.builder()
            .id("log_search")
            .title("Log Search")
            .riskLevel("low")
            .operationType("read")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("log_search")
            .runtimeMode("agent_chat")
            .requestId("req-confirm-level")
            .conversationId("conv-confirm-level")
            .tenantId("tenant-ops")
            .userId("ops-user")
            .allowedTools(List.of("log_search"))
            .toolInput(ToolInput.builder().userId("ops-user").parameters(Map.of("query", "error")).build())
            .attributes(Map.of("runtimeLevel", "confirm_required"))
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("confirmation_required");
        assertThat(execution.audit()).containsEntry("runtimeLevel", "confirm_required");
        assertThat(execution.audit()).containsKey("confirmation");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rememberedUserAllowOverridesAskToolAndParameterPolicies() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .riskLevel("low")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.success("ok-1"),
            ToolOutput.success("ok-2")
        );

        McpPolicyProperties mcpPolicy = new McpPolicyProperties();
        mcpPolicy.setToolPolicy(Map.of("document_search", "ask_before_execute"));
        mcpPolicy.setParameterPolicy(Map.of(
            "document_search",
            Map.of("document_ids", "ask_before_execute")
        ));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            mcpPolicy,
            List.of(),
            List.of()
        );

        ToolRuntimeRequest firstRequest = documentSearchRequest(Map.of());
        ToolRuntimeExecution first = service.execute(firstRequest);
        Map<String, Object> confirmation = (Map<String, Object>) first.audit().get("confirmation");
        String token = String.valueOf(confirmation.get("token"));

        ToolRuntimeExecution confirmed = service.execute(documentSearchRequest(Map.of(
            "mcpConfirmation",
            Map.of(
                "token", token,
                "approved", true,
                "remember", "tool_auto_execute"
            )
        )));
        ToolRuntimeExecution remembered = service.execute(firstRequest);

        assertThat(first.outcome()).isEqualTo("confirmation_required");
        assertThat(confirmed.output().isSuccess()).isTrue();
        assertThat(remembered.output().isSuccess()).isTrue();
        assertThat(remembered.audit().get("matchedPolicyRules").toString()).contains("user_tool_policy=auto_execute");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowDeniesSkippedRequiredStepAndAllowsAfterDependencyCompletes() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("query_customer_basic_info")).thenReturn(ToolMetadata.builder()
            .id("query_customer_basic_info")
            .title("Basic Info")
            .build());
        when(toolRegistry.getToolMetadata("query_customer_asset_summary")).thenReturn(ToolMetadata.builder()
            .id("query_customer_asset_summary")
            .title("Asset Summary")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        McpWorkflowProperties workflowProperties = new McpWorkflowProperties();
        McpWorkflowProperties.WorkflowSpec workflow = new McpWorkflowProperties.WorkflowSpec();
        McpWorkflowProperties.WorkflowStep first = new McpWorkflowProperties.WorkflowStep();
        first.setStep(1);
        first.setTool("query_customer_basic_info");
        first.setRequired(true);
        McpWorkflowProperties.WorkflowStep second = new McpWorkflowProperties.WorkflowStep();
        second.setStep(2);
        second.setTool("query_customer_asset_summary");
        second.setRequired(true);
        workflow.setSteps(List.of(first, second));
        workflowProperties.setWorkflows(Map.of("customer_asset_analysis", workflow));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            workflowProperties,
            List.of(),
            List.of()
        );

        ToolRuntimeRequest skipped = workflowRequest("query_customer_asset_summary", Map.of());
        ToolRuntimeExecution denied = service.execute(skipped);
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.output().getErrorMessage()).contains("required previous steps");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());

        ToolRuntimeExecution firstExecution = service.execute(workflowRequest("query_customer_basic_info", Map.of()));
        ToolRuntimeExecution secondExecution = service.execute(skipped);

        assertThat(firstExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.output().isSuccess()).isTrue();
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void agentWorkflowConfigDeniesSkippedStepWithoutGlobalWorkflow() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("query_customer_basic_info")).thenReturn(ToolMetadata.builder()
            .id("query_customer_basic_info")
            .title("Basic Info")
            .build());
        when(toolRegistry.getToolMetadata("query_customer_asset_summary")).thenReturn(ToolMetadata.builder()
            .id("query_customer_asset_summary")
            .title("Asset Summary")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );

        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "ops_agent_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 4),
            "steps", List.of(
                Map.of("step", 1, "tool", "query_customer_basic_info", "required", true),
                Map.of("step", 2, "tool", "query_customer_asset_summary", "required", true)
            )
        );

        ToolRuntimeExecution denied = service.execute(agentWorkflowRequest(
            "query_customer_asset_summary",
            workflowConfig
        ));
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.output().getErrorMessage()).contains("required previous steps");

        ToolRuntimeExecution firstExecution = service.execute(agentWorkflowRequest(
            "query_customer_basic_info",
            workflowConfig
        ));
        ToolRuntimeExecution secondExecution = service.execute(agentWorkflowRequest(
            "query_customer_asset_summary",
            workflowConfig
        ));

        assertThat(firstExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.audit().get("matchedPolicyRules").toString()).contains("workflow.ops_agent_workflow.active");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void authoritativeDagOverridesStaleSequentialOrderForTemplateProtocol() {
        String asset = "mcp_chatchat_mcp_server_api_asset_query";
        String query = "mcp_chatchat_mcp_server_api_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        for (String tool : List.of(asset, query, execute)) {
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool).title(tool).build());
        }
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> staleWorkflow = Map.of(
            "enabled", true,
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", asset, "required", true),
                Map.of("step", 2, "tool", execute, "required", true),
                Map.of("step", 3, "tool", query, "required", true)
            )
        );
        List<Map<String, Object>> authoritativeDag = List.of(
            Map.of("tool", asset, "dependsOnTools", List.of()),
            Map.of("tool", query, "dependsOnTools", List.of(asset)),
            Map.of("tool", execute, "dependsOnTools", List.of(query))
        );
        java.util.function.Function<String, ToolRuntimeRequest> request = tool -> ToolRuntimeRequest.builder()
            .toolName(tool)
            .runtimeMode("interpretation_plan")
            .requestId("req-authoritative-template-workflow")
            .conversationId("conv-authoritative-template-workflow")
            .tenantId("tenant-1")
            .userId("user-1")
            .allowedTools(List.of(asset, query, execute))
            .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build())
            .attributes(Map.of(
                "mcpWorkflow", staleWorkflow,
                "authoritativeWorkflowDag", authoritativeDag
            ))
            .build();

        ToolRuntimeExecution assetExecution = service.execute(request.apply(asset));
        ToolRuntimeExecution queryExecution = service.execute(request.apply(query));

        assertThat(assetExecution.output().isSuccess()).isTrue();
        assertThat(queryExecution.output().isSuccess()).isTrue();
        assertThat(queryExecution.audit().get("matchedPolicyRules").toString())
            .contains("authoritative_workflow_dag." + query + "=[" + asset + "]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentWorkflowArrayDeniesDatabaseExecuteUntilDependenciesAndConfirmation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        for (String tool : List.of("asset_query", "template_query", "database_query", "database_execute")) {
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool)
                .title(tool)
                .riskLevel("low")
                .operationType("database_execute".equals(tool) ? "write" : "read")
                .categories(List.of("mcp"))
                .build());
        }
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );

        List<Map<String, Object>> workflow = List.of(
            Map.of("step", "asset_discovery", "tool", "asset_query", "required", true, "dependsOn", List.of(),
                "confirmation", "none", "executionStrategy", "deterministic_first"),
            Map.of("step", "template_retrieval", "tool", "template_query", "required", true,
                "dependsOn", List.of("asset_discovery"), "confirmation", "none", "executionStrategy", "retrieve_rank_then_plan"),
            Map.of("step", "database_diagnosis", "tool", "database_query", "required", true,
                "dependsOn", List.of("asset_discovery", "template_retrieval"), "confirmation", "none", "executionStrategy", "read_only_query"),
            Map.of("step", "database_change", "tool", "database_execute", "required", false,
                "dependsOn", List.of("asset_discovery", "template_retrieval", "database_diagnosis"),
                "confirmation", "required_for_write", "executionStrategy", "confirm_then_execute")
        );
        List<String> tools = List.of("asset_query", "template_query", "database_query", "database_execute");

        ToolRuntimeExecution denied = service.execute(agentWorkflowRequest(
            "database_execute",
            workflow,
            tools,
            "req-db-workflow",
            "conv-db-workflow"
        ));
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.output().getErrorMessage())
            .contains("required previous steps")
            .contains("asset_query", "template_query", "database_query");

        assertThat(service.execute(agentWorkflowRequest("asset_query", workflow, tools,
            "req-db-workflow", "conv-db-workflow")).output().isSuccess()).isTrue();
        assertThat(service.execute(agentWorkflowRequest("template_query", workflow, tools,
            "req-db-workflow", "conv-db-workflow")).output().isSuccess()).isTrue();
        assertThat(service.execute(agentWorkflowRequest("database_query", workflow, tools,
            "req-db-workflow", "conv-db-workflow")).output().isSuccess()).isTrue();

        ToolRuntimeExecution needsConfirmation = service.execute(agentWorkflowRequest(
            "database_execute",
            workflow,
            tools,
            "req-db-workflow",
            "conv-db-workflow"
        ));
        assertThat(needsConfirmation.outcome()).isEqualTo("confirmation_required");
        assertThat(needsConfirmation.audit().get("matchedPolicyRules").toString())
            .contains("workflow.agent_workflow.database_execute.confirmation=ask_before_execute");
        Map<String, Object> confirmation = (Map<String, Object>) needsConfirmation.audit().get("confirmation");
        String token = String.valueOf(confirmation.get("token"));

        ToolRuntimeExecution confirmed = service.execute(ToolRuntimeRequest.builder()
            .toolName("database_execute")
            .runtimeMode("agent_chat")
            .requestId("req-db-workflow")
            .conversationId("conv-db-workflow")
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(tools)
            .toolInput(ToolInput.builder().userId("user-agent-workflow").parameters(Map.of()).build())
            .attributes(Map.of(
                "mcpWorkflow", workflow,
                "mcpConfirmation", Map.of(
                    "token", token,
                    "approved", true,
                    "decision", "allow_once"
                )
            ))
            .build());

        assertThat(confirmed.output().isSuccess()).isTrue();
        verify(toolRegistry, times(4)).executeEnhancedTool(any(), any());
    }

    @Test
    void agentWorkflowAutoExecuteOverridesParameterConfirmation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .riskLevel("low")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        McpPolicyProperties mcpPolicy = new McpPolicyProperties();
        mcpPolicy.setToolPolicy(Map.of("document_search", "ask_before_execute"));
        mcpPolicy.setParameterPolicy(Map.of(
            "document_search",
            Map.of("document_ids", "ask_before_execute")
        ));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            mcpPolicy,
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );

        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "live_data_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 6),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "tool", "mcp_chatchat_mcp_server_document_search",
                    "required", true,
                    "confirmation", "auto_execute"
                )
            )
        );

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("document_search")
            .runtimeMode("agent_chat")
            .requestId("req-agent-workflow-auto")
            .conversationId("conv-agent-workflow-auto")
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder()
                .userId("user-agent-workflow")
                .parameters(Map.of("query", "Kafka Connect", "document_ids", List.of("doc-1")))
                .build())
            .attributes(Map.of("mcpWorkflow", workflowConfig))
            .build());

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(execution.audit().get("matchedPolicyRules").toString())
            .contains("tool_policy.document_search=ask_before_execute")
            .contains("workflow.live_data_workflow.document_search.confirmation=auto_execute");
        verify(toolRegistry, times(1)).executeEnhancedTool(any(), any());
    }

    @Test
    void hybridWorkflowAllowsParallelStageInAnyOrderBeforeNextStage() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().title("Tool").build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "hybrid_research",
            "executionStrategy", Map.of("mode", "hybrid", "stopOnError", true),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "name", "internal_retrieval",
                    "parallelSteps", List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search"),
                    "required", true
                ),
                Map.of("step", 2, "name", "external_verify", "tool", "mcp_xxx_web_search", "required", true)
            )
        );

        ToolRuntimeExecution deniedWeb = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        assertThat(deniedWeb.output().isSuccess()).isFalse();
        assertThat(deniedWeb.output().getErrorMessage())
            .contains("mcp_xxx_document_search")
            .contains("mcp_xxx_knowledge_search");

        ToolRuntimeExecution knowledgeFirst = service.execute(agentWorkflowRequest(
            "mcp_xxx_knowledge_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution webStillDenied = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution documentSecond = service.execute(agentWorkflowRequest(
            "mcp_xxx_document_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution webAllowed = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));

        assertThat(knowledgeFirst.output().isSuccess()).isTrue();
        assertThat(webStillDenied.output().isSuccess()).isFalse();
        assertThat(webStillDenied.output().getErrorMessage()).contains("mcp_xxx_document_search");
        assertThat(documentSecond.output().isSuccess()).isTrue();
        assertThat(webAllowed.output().isSuccess()).isTrue();
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowFailureDoesNotLeakAcrossRequestsInSameConversation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_web_search")).thenReturn(ToolMetadata.builder()
            .id("mcp_chatchat_mcp_server_web_search")
            .title("Web Search")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.failure("network failed"),
            ToolOutput.success("fresh result")
        );

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "live_data_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", "mcp_chatchat_mcp_server_web_search", "required", true)
            )
        );

        ToolRuntimeExecution failed = service.execute(agentWorkflowRequest(
            "mcp_chatchat_mcp_server_web_search",
            workflowConfig,
            List.of("mcp_chatchat_mcp_server_web_search"),
            "req-live-data-1",
            "conv-live-data"
        ));
        ToolRuntimeExecution nextRequest = service.execute(agentWorkflowRequest(
            "mcp_chatchat_mcp_server_web_search",
            workflowConfig,
            List.of("mcp_chatchat_mcp_server_web_search"),
            "req-live-data-2",
            "conv-live-data"
        ));

        assertThat(failed.output().isSuccess()).isFalse();
        assertThat(nextRequest.output().isSuccess()).isTrue();
        assertThat(nextRequest.output().getDataAsString()).isEqualTo("fresh result");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowFailureDoesNotBlockRewrittenPlanAttemptInSameRun() {
        String toolName = "mcp_chatchat_mcp_server_database_asset_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("Database asset search")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.failure("invalid planner input"),
            ToolOutput.success("repaired asset result")
        );
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "database_fact_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(Map.of("step", 1, "tool", toolName, "required", true))
        );

        ToolRuntimeExecution failed = service.execute(agentWorkflowAttemptRequest(toolName, workflowConfig, 0));
        ToolRuntimeExecution stoppedInSameAttempt = service.execute(agentWorkflowAttemptRequest(toolName, workflowConfig, 0));
        ToolRuntimeExecution rewrittenAttempt = service.execute(agentWorkflowAttemptRequest(toolName, workflowConfig, 1));

        assertThat(failed.output().isSuccess()).isFalse();
        assertThat(stoppedInSameAttempt.output().getErrorMessage()).contains("previous required step failed");
        assertThat(rewrittenAttempt.output().isSuccess()).isTrue();
        assertThat(rewrittenAttempt.output().getDataAsString()).isEqualTo("repaired asset result");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void successfulWorkflowDependenciesAreInheritedAcrossPlanAttemptsInSameRun() {
        List<String> tools = List.of("database_asset_search", "database_ops_template_search", "sql_query_execute");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        tools.forEach(tool -> when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool).title(tool).categories(List.of("mcp")).build()));
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> workflow = diagnosticWorkflow(tools);

        ToolRuntimeExecution asset = service.execute(diagnosticAttemptRequest(
            tools.get(0), workflow, tools, 0, "asset-a"));
        ToolRuntimeExecution templates = service.execute(diagnosticAttemptRequest(
            tools.get(1), workflow, tools, 1, "asset-a"));
        ToolRuntimeExecution sql = service.execute(diagnosticAttemptRequest(
            tools.get(2), workflow, tools, 2, "asset-a"));

        assertThat(asset.output().isSuccess()).isTrue();
        assertThat(templates.output().isSuccess()).isTrue();
        assertThat(sql.output().isSuccess()).isTrue();
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowDependenciesMatchConfiguredFullMcpNamesToRuntimeShortNames() {
        List<String> runtimeTools = List.of(
            "database_asset_search",
            "database_ops_template_search",
            "sql_query_execute"
        );
        List<String> configuredTools = runtimeTools.stream()
            .map(tool -> "mcp_chatchat_mcp_server_" + tool)
            .toList();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        runtimeTools.forEach(tool -> when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool).title(tool).categories(List.of("mcp")).build()));
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> workflow = diagnosticWorkflow(configuredTools);

        ToolRuntimeExecution asset = service.execute(diagnosticAttemptRequest(
            runtimeTools.get(0), workflow, runtimeTools, 0, "asset-a"));
        ToolRuntimeExecution templates = service.execute(diagnosticAttemptRequest(
            runtimeTools.get(1), workflow, runtimeTools, 1, "asset-a"));
        ToolRuntimeExecution sql = service.execute(diagnosticAttemptRequest(
            runtimeTools.get(2), workflow, runtimeTools, 2, "asset-a"));

        assertThat(asset.output().isSuccess()).isTrue();
        assertThat(templates.output().isSuccess()).isTrue();
        assertThat(sql.output().isSuccess()).isTrue();
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowDependenciesMatchAnUnseenRuntimeNamespaceWithoutServerSpecificRules() {
        List<String> runtimeTools = List.of(
            "database_asset_search",
            "database_ops_template_search",
            "sql_query_execute"
        );
        String namespace = "mcp_tenant_" + System.nanoTime() + "_capability_gateway_";
        List<String> configuredTools = runtimeTools.stream().map(namespace::concat).toList();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        runtimeTools.forEach(tool -> when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool).title(tool).categories(List.of("mcp")).build()));
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> workflow = diagnosticWorkflow(configuredTools);

        for (int index = 0; index < runtimeTools.size(); index++) {
            ToolRuntimeExecution execution = service.execute(diagnosticAttemptRequest(
                runtimeTools.get(index), workflow, runtimeTools, index, "asset-dynamic"));
            assertThat(execution.output().isSuccess()).isTrue();
        }

        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    @Test
    void completedWorkflowFactForOneAssetCannotSatisfyAnotherAsset() {
        List<String> tools = List.of("database_asset_search", "database_ops_template_search", "sql_query_execute");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        tools.forEach(tool -> when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool).title(tool).categories(List.of("mcp")).build()));
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> workflow = diagnosticWorkflow(tools);

        assertThat(service.execute(diagnosticAttemptRequest(
            tools.get(0), workflow, tools, 0, "asset-a")).output().isSuccess()).isTrue();
        ToolRuntimeExecution blocked = service.execute(diagnosticAttemptRequest(
            tools.get(1), workflow, tools, 1, "asset-b"));
        ToolRuntimeExecution sameAsset = service.execute(diagnosticAttemptRequest(
            tools.get(1), workflow, tools, 1, "asset-a"));

        assertThat(blocked.output().isSuccess()).isFalse();
        assertThat(blocked.output().getErrorMessage()).contains("required previous steps");
        assertThat(blocked.audit())
            .containsEntry("executionStatus", "BLOCKED")
            .containsEntry("blockedBeforeInvocation", true)
            .containsEntry("blockedReason", "workflow_dependency_unsatisfied")
            .containsEntry("remoteToolInvoked", false);
        assertThat(sameAsset.output().isSuccess()).isTrue();
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void canonicalAssetIdCanContinueAWorkflowWhenDagPreservesTheDiscoveredAlias() {
        List<String> tools = List.of("generated_asset_search", "generated_template_search");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        tools.forEach(tool -> when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool).title(tool).categories(List.of("mcp")).build()));
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> workflow = Map.of(
            "enabled", true,
            "workflow", "generated_diagnostic",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 4),
            "steps", List.of(
                Map.of("step", 1, "tool", tools.get(0), "required", true),
                Map.of("step", 2, "tool", tools.get(1), "required", true)
            )
        );

        ToolRuntimeExecution discovered = service.execute(diagnosticAliasRequest(
            tools.get(0), workflow, tools, 0,
            Map.of("executionContext", Map.of("assetName", "generated-target-alias")),
            Map.of()
        ));
        ToolRuntimeExecution dependent = service.execute(diagnosticAliasRequest(
            tools.get(1), workflow, tools, 1,
            Map.of("executionContext", Map.of("assetId", "generated-canonical-id")),
            Map.of("workflowContext", Map.of("workflowTargetRef", "generated-target-alias"))
        ));

        assertThat(discovered.output().isSuccess()).isTrue();
        assertThat(dependent.output().isSuccess()).isTrue();
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void sequentialBatchPreservesOrderAndContinuesAfterOneFailure() {
        String shortName = "sql_query_execute";
        String fullName = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        for (String tool : List.of(shortName, fullName)) {
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool).title(tool).categories(List.of("mcp")).build());
        }
        List<String> executedTemplates = new ArrayList<>();
        when(toolRegistry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            ToolInput input = invocation.getArgument(1);
            String template = String.valueOf(input.getParameters().get("templateCode"));
            executedTemplates.add(template);
            return "ORACLE_LOCKS".equals(template)
                ? ToolOutput.failure("lock query failed")
                : ToolOutput.success(Map.of("template", template));
        });
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setDefaultRetryAttempts(0);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), runtimeProperties, new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        List<Map<String, Object>> calls = List.of(
            batchCall("instance", shortName, "ORACLE_INSTANCE_STATUS"),
            batchCall("sessions", fullName, "ORACLE_SESSION_OVERVIEW"),
            batchCall("locks", shortName, "ORACLE_LOCKS"),
            batchCall("events", fullName, "ORACLE_SYSTEM_EVENTS"),
            batchCall("tablespace", shortName, "ORACLE_TABLESPACE_SIZE")
        );

        ToolRuntimeExecution execution = service.execute(batchRequest(calls, false, Map.of()));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.summary().success()).isEqualTo(4);
        assertThat(result.summary().failed()).isEqualTo(1);
        assertThat(result.summary().remoteToolInvocations()).isEqualTo(5);
        assertThat(result.summary().total()).isEqualTo(result.results().size()).isEqualTo(5);
        assertThat(execution.audit())
            .containsEntry("declaredCheckCount", 5)
            .containsEntry("compiledCallCount", 5)
            .containsEntry("executedCallCount", 5)
            .containsEntry("resultCount", 5)
            .containsEntry("batchResultCountConsistent", true);
        assertThat(result.results()).extracting(item -> item.callId())
            .containsExactly("instance", "sessions", "locks", "events", "tablespace");
        assertThat(result.results()).extracting(item -> item.assetId())
            .containsOnly("asset-a");
        assertThat(result.results()).extracting(item -> item.assetDisplayName())
            .containsOnly("Oracle DEV");
        assertThat(result.results()).extracting(item -> item.assetToolName())
            .containsOnly("db_query_oracle_dev");
        assertThat(result.results()).extracting(item -> item.sequence())
            .containsExactly(1, 2, 3, 4, 5);
        assertThat(executedTemplates).containsExactly(
            "ORACLE_INSTANCE_STATUS",
            "ORACLE_SESSION_OVERVIEW",
            "ORACLE_LOCKS",
            "ORACLE_SYSTEM_EVENTS",
            "ORACLE_TABLESPACE_SIZE"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void templateDeclaredRequiredFieldsReduceQualityWithoutReducingExecutionCoverage() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            ToolInput input = invocation.getArgument(1);
            String template = String.valueOf(input.getParameters().get("templateCode"));
            return "ORACLE_INSTANCE_STATUS".equals(template)
                ? ToolOutput.success(Map.of("rows", List.of(Map.of("INSTANCE_NAME", "oraclewind", "STATUS", "OPEN"))))
                : ToolOutput.success(Map.of("rows", List.of(Map.of("TABLESPACE_NAME", "USERS", "SIZE_MB", 500))));
        });
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> instance = new LinkedHashMap<>(
            batchCall("instance_status", toolName, "ORACLE_INSTANCE_STATUS"));
        instance.put("requiredFields", List.of("INSTANCE_NAME", "STATUS"));
        Map<String, Object> tablespace = new LinkedHashMap<>(
            batchCall("tablespace", toolName, "ORACLE_TABLESPACE_HEALTH"));
        tablespace.put("requiredMetrics", List.of(
            "TABLESPACE_NAME", "TOTAL_MB", "USED_MB", "FREE_MB", "USED_PERCENT"));
        tablespace.put("purpose", "capacity_health");
        tablespace.put("healthCapability", true);

        ToolRuntimeExecution execution = service.execute(
            batchRequest(List.of(instance, tablespace), false, Map.of()));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.summary().success()).isEqualTo(2);
        assertThat(result.summary().failed()).isZero();
        assertThat(result.results()).extracting(ToolCallResult::status)
            .containsExactly("SUCCESS", "SUCCESS");
        assertThat(result.results().get(1).evidencePolicy().requiredMetrics())
            .containsExactly("TABLESPACE_NAME", "TOTAL_MB", "USED_MB", "FREE_MB", "USED_PERCENT");
        assertThat(result.results().get(1).error()).isEmpty();
    }

    @Test
    void batchResultCannotClaimSuccessWhenDeclaredAndReturnedCountsDiffer() {
        ToolCallBatchResult result = new ToolCallBatchResult(
            "incomplete-batch",
            "SEQUENTIAL",
            "start",
            "end",
            "SUCCESS",
            new ToolCallBatchResult.Summary(5, 1, 0, 0, 0, 1),
            List.of(new ToolCallResult(
                "instance_status",
                "sql_query_execute",
                "ORACLE_INSTANCE_STATUS",
                "asset-a",
                "SUCCESS",
                10,
                "evidence-1",
                Map.of("STATUS", "OPEN"),
                Map.of()
            ))
        );

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.summary().total()).isEqualTo(5);
        assertThat(result.results()).hasSize(1);
    }

    @Test
    void compilationGapProducesExplicitNotExecutedResultAndCardinality() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        when(toolRegistry.executeEnhancedTool(any(), any()))
            .thenReturn(ToolOutput.success(Map.of("ok", true)));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        List<Map<String, Object>> calls = List.of(
            batchCall("instance_status", toolName, "ORACLE_INSTANCE_STATUS"),
            batchCall("current_sessions", toolName, "ORACLE_SESSION_OVERVIEW"),
            batchCall("lock_wait", toolName, "ORACLE_LOCKS"),
            batchCall("system_wait_events", toolName, "ORACLE_SYSTEM_EVENTS")
        );
        Map<String, Object> attributes = Map.of(
            "diagnosticRunId", "run-oracle",
            "diagnosticDeclaredCheckCount", 5,
            "diagnosticCompiledCallCount", 4,
            "diagnosticMissingAuthorizedCheckIds", List.of("tablespace_usage")
        );

        ToolRuntimeExecution execution = service.execute(batchRequest(calls, false, attributes));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("BATCH_COMPILATION_INCOMPLETE");
        assertThat(result.cardinality())
            .isEqualTo(new ToolCallBatchResult.Cardinality(5, 4, 4, 4));
        assertThat(result.results()).hasSize(5);
        assertThat(result.results().get(4)).satisfies(missing -> {
            assertThat(missing.callId()).isEqualTo("tablespace_usage");
            assertThat(missing.checkId()).isEqualTo("tablespace_usage");
            assertThat(missing.status()).isEqualTo("NOT_EXECUTED");
            assertThat(missing.invoked()).isFalse();
            assertThat(missing.error()).containsEntry("code", "AUTHORIZED_TEMPLATE_NOT_FOUND");
        });
    }

    @Test
    void diagnosticBatchRejectsDuplicateCallsMissingAssetAndCrossAssetMixing() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> diagnostic = Map.of("diagnosticDeclaredCheckCount", 2);

        ToolRuntimeExecution duplicate = service.execute(batchRequest(List.of(
            batchCall("same", toolName, "ONE"),
            batchCall("same", toolName, "TWO")
        ), false, diagnostic));
        ToolRuntimeExecution missingAsset = service.execute(batchRequest(List.of(
            Map.of(
                "callId", "one",
                "toolName", toolName,
                "arguments", Map.of("templateCode", "ONE")
            )
        ), false, Map.of("diagnosticDeclaredCheckCount", 1)));
        ToolRuntimeExecution crossAsset = service.execute(batchRequest(List.of(
            batchCall("one", toolName, "ONE"),
            Map.of(
                "callId", "two",
                "toolName", toolName,
                "arguments", Map.of(
                    "templateCode", "TWO",
                    "executionContext", Map.of("assetId", "asset-b")
                )
            )
        ), false, diagnostic));

        assertThat(duplicate.output().getExceptionType()).isEqualTo("BATCH_CALL_ID_INVALID");
        assertThat(missingAsset.output().getExceptionType()).isEqualTo("BATCH_ASSET_ID_REQUIRED");
        assertThat(crossAsset.output().getExceptionType()).isEqualTo("BATCH_ASSET_MISMATCH");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void emptyDiagnosticResultUsesPerCheckTemplatePolicy() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success(List.of()));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> emptyIsHealthy = new LinkedHashMap<>(batchCall("locks", toolName, "ORACLE_LOCKS"));
        emptyIsHealthy.put("emptyResultIsSuccess", true);
        Map<String, Object> emptyNeedsRows =
            new LinkedHashMap<>(batchCall("sessions", toolName, "ORACLE_SESSION_OVERVIEW"));
        emptyNeedsRows.put("emptyResultIsSuccess", false);

        ToolRuntimeExecution execution = service.execute(batchRequest(
            List.of(emptyIsHealthy, emptyNeedsRows),
            false,
            Map.of("diagnosticDeclaredCheckCount", 2)
        ));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.results()).extracting(ToolCallResult::status)
            .containsExactly("SUCCESS", "RESULT_MISSING");
        assertThat(result.results()).extracting(ToolCallResult::evidenceUsable)
            .containsExactly(true, false);
    }

    @Test
    void templateExecutionLayerIgnoresLegacyStopFlagAndReturnsEveryFailure() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.failure("first failed"));
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setDefaultRetryAttempts(0);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), runtimeProperties, new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        List<Map<String, Object>> calls = List.of(
            batchCall("first", toolName, "ONE"),
            batchCall("second", toolName, "TWO"),
            batchCall("third", toolName, "THREE")
        );

        ToolRuntimeExecution execution = service.execute(batchRequest(calls, true, Map.of()));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.summary().failed()).isEqualTo(3);
        assertThat(result.summary().skipped()).isZero();
        assertThat(result.results()).extracting(item -> item.status())
            .containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(result.results()).allSatisfy(item ->
            assertThat(item.error()).containsEntry("message", "first failed"));
        assertThat(execution.audit())
            .containsEntry("templateExecutionLayer", true)
            .containsEntry("failureIsolation", true)
            .containsEntry("requestedStopOnFailureIgnored", true);
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    @Test
    void batchResolvesSemanticChildToolNameToRegisteredExecutor() {
        String registeredTool = "mcp_chatchat_mcp_server_api_template_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(registeredTool)).thenReturn(ToolMetadata.builder()
            .id(registeredTool).title("API template executor").categories(List.of("mcp")).build());
        when(toolRegistry.executeEnhancedTool(eq(registeredTool), any()))
            .thenReturn(ToolOutput.success(Map.of("records", List.of())));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName(registeredTool)
            .runtimeMode("interpretation_plan")
            .requestId("semantic-batch")
            .conversationId("semantic-conversation")
            .tenantId("tenant-1")
            .userId("user-1")
            .allowedTools(List.of(registeredTool))
            .toolInput(ToolInput.builder().parameters(Map.of(
                "executionMode", "SEQUENTIAL",
                "stopOnFailure", false,
                "calls", List.of(Map.of(
                    "callId", "customer-orders",
                    "toolName", "api_template_execute",
                    "arguments", Map.of("templateId", "runtime-selected-template", "parameters", Map.of())
                ))
            )).build())
            .build();

        ToolRuntimeExecution execution = service.execute(request);
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(result.summary().success()).isEqualTo(1);
        assertThat(result.results()).singleElement().satisfies(child ->
            assertThat(child.toolName()).isEqualTo(registeredTool));
        verify(toolRegistry).executeEnhancedTool(eq(registeredTool), any());
    }

    @Test
    void expiredDiagnosticDeadlineBlocksWholeBatchWithoutRemoteCalls() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).title(toolName).categories(List.of("mcp")).build());
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        List<Map<String, Object>> calls = List.of(
            batchCall("first", toolName, "ONE"),
            batchCall("second", toolName, "TWO")
        );

        ToolRuntimeExecution execution = service.execute(batchRequest(
            calls, false, Map.of("__agentDeadlineAt", System.currentTimeMillis() - 1)));
        ToolCallBatchResult result = (ToolCallBatchResult) execution.output().getData();

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(result.status()).isEqualTo("TIME_BUDGET_EXHAUSTED");
        assertThat(result.summary().remoteToolInvocations()).isZero();
        assertThat(result.results()).extracting(item -> item.status())
            .containsExactly("TIME_BUDGET_EXHAUSTED", "NOT_EXECUTED");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void rejectsOversizedBatchBeforeAnyRemoteInvocation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder()
            .id("sql_query_execute").categories(List.of("mcp")).build());
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setMaxBatchPayloadBytes(1024);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), runtimeProperties, new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        Map<String, Object> oversized = Map.of(
            "callId", "large",
            "toolName", "sql_query_execute",
            "arguments", Map.of("templateCode", "CHECK", "value", "x".repeat(2_000))
        );

        ToolRuntimeExecution execution = service.execute(batchRequest(List.of(oversized), false, Map.of()));

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.output().getExceptionType()).isEqualTo("BATCH_PAYLOAD_TOO_LARGE");
        assertThat(execution.audit())
            .containsEntry("blockedBeforeInvocation", true)
            .containsEntry("remoteToolInvoked", false);
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void rejectsCallLimitAndNestedBatchBeforeAnyRemoteInvocation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder()
            .id("sql_query_execute").categories(List.of("mcp")).build());
        ToolRuntimeProperties runtimeProperties = properties();
        runtimeProperties.setMaxBatchCalls(1);
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), runtimeProperties, new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());

        ToolRuntimeExecution tooMany = service.execute(batchRequest(List.of(
            batchCall("one", "sql_query_execute", "ONE"),
            batchCall("two", "sql_query_execute", "TWO")
        ), false, Map.of()));
        Map<String, Object> nested = Map.of(
            "callId", "nested",
            "toolName", "sql_query_execute",
            "arguments", Map.of(
                "executionMode", "SEQUENTIAL",
                "calls", List.of(batchCall("inner", "sql_query_execute", "INNER"))
            )
        );
        ToolRuntimeExecution nestedResult = service.execute(batchRequest(List.of(nested), false, Map.of()));

        assertThat(tooMany.output().getExceptionType()).isEqualTo("BATCH_CALL_LIMIT_EXCEEDED");
        assertThat(nestedResult.output().getExceptionType()).isEqualTo("BATCH_NESTING_NOT_ALLOWED");
        assertThat(tooMany.audit()).containsEntry("remoteToolInvoked", false);
        assertThat(nestedResult.audit()).containsEntry("remoteToolInvoked", false);
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void rejectsNonWhitelistedOuterOrChildBatchTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().build());
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry, new ObjectMapper(), properties(), new McpPolicyProperties(),
            new McpWorkflowProperties(), List.of(), List.of());
        ToolRuntimeRequest invalidOuter = batchRequest(
            List.of(batchCall("one", "sql_query_execute", "ONE")), false, Map.of());
        invalidOuter.setToolName("document_search");
        Map<String, Object> invalidChild = Map.of(
            "callId", "unsafe",
            "toolName", "document_search",
            "arguments", Map.of("query", "not allowed in batch")
        );

        ToolRuntimeExecution outer = service.execute(invalidOuter);
        ToolRuntimeExecution child = service.execute(batchRequest(List.of(invalidChild), false, Map.of()));

        assertThat(outer.output().getExceptionType()).isEqualTo("BATCH_TOOL_NOT_ALLOWED");
        assertThat(child.output().getExceptionType()).isEqualTo("BATCH_TOOL_NOT_ALLOWED");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void failedSequentialStepCountsAsAttemptSoFallbackCanContinueWhenStopOnErrorIsDisabled() {
        String assetSearch = "database_asset_search";
        String templateSearch = "database_template_search";
        String hostFallback = "host_resource_query";
        List<String> tools = List.of(assetSearch, templateSearch, hostFallback);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        for (String tool : tools) {
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool)
                .title(tool)
                .categories(List.of("mcp"))
                .build());
        }
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.success(Map.of("asset", "DEV database")),
            ToolOutput.failure("invalid optional selector"),
            ToolOutput.success(Map.of("cpuPct", 22, "memoryPct", 48, "diskPct", 61))
        );
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "environment_health_analysis",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", false),
            "steps", List.of(
                Map.of("step", 1, "tool", assetSearch, "required", true),
                Map.of("step", 2, "tool", templateSearch, "required", true),
                Map.of("step", 3, "tool", hostFallback, "required", true)
            )
        );

        ToolRuntimeExecution assetResult = service.execute(agentWorkflowRequest(
            assetSearch, workflowConfig, tools, "req-health-fallback", "conv-health-fallback"));
        ToolRuntimeExecution templateResult = service.execute(agentWorkflowRequest(
            templateSearch, workflowConfig, tools, "req-health-fallback", "conv-health-fallback"));
        ToolRuntimeExecution fallbackResult = service.execute(agentWorkflowRequest(
            hostFallback, workflowConfig, tools, "req-health-fallback", "conv-health-fallback"));

        assertThat(assetResult.output().isSuccess()).isTrue();
        assertThat(templateResult.output().isSuccess()).isFalse();
        assertThat(fallbackResult.output().isSuccess()).isTrue();
        assertThat(fallbackResult.output().getData()).isEqualTo(
            Map.of("cpuPct", 22, "memoryPct", 48, "diskPct", 61));
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    private ToolRuntimeProperties properties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setCircuitBreakerFailureThreshold(3);
        properties.setCircuitBreakerOpenSeconds(30);
        properties.setDefaultRetryAttempts(0);
        return properties;
    }

    private ToolRuntimeRequest workflowRequest(String toolName, Map<String, Object> parameters) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId("req-workflow")
            .conversationId("conv-workflow")
            .tenantId("tenant-1")
            .userId("user-workflow")
            .allowedTools(List.of("query_customer_basic_info", "query_customer_asset_summary"))
            .toolInput(ToolInput.builder().userId("user-workflow").parameters(parameters).build())
            .attributes(Map.of("executionPlan", Map.of("workflow", "customer_asset_analysis")))
            .build();
    }

    private ToolRuntimeRequest agentWorkflowRequest(String toolName, Object workflowConfig) {
        return agentWorkflowRequest(toolName, workflowConfig, List.of("query_customer_basic_info", "query_customer_asset_summary"));
    }

    private ToolRuntimeRequest agentWorkflowRequest(String toolName, Object workflowConfig, List<String> allowedTools) {
        return agentWorkflowRequest(toolName, workflowConfig, allowedTools, "req-agent-workflow", "conv-agent-workflow");
    }

    private ToolRuntimeRequest agentWorkflowRequest(String toolName,
                                                    Object workflowConfig,
                                                    List<String> allowedTools,
                                                    String requestId,
                                                    String conversationId) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId(requestId)
            .conversationId(conversationId)
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder().userId("user-agent-workflow").parameters(Map.of()).build())
            .attributes(Map.of("mcpWorkflow", workflowConfig))
            .build();
    }

    private ToolRuntimeRequest agentWorkflowAttemptRequest(String toolName,
                                                           Object workflowConfig,
                                                           int attempt) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("interpretation_plan")
            .requestId("req-rewrite-attempt")
            .conversationId("conv-rewrite-attempt")
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(List.of(toolName))
            .toolInput(ToolInput.builder().userId("user-agent-workflow").parameters(Map.of()).build())
            .attributes(Map.of(
                "mcpWorkflow", workflowConfig,
                "__agentRunId", "run-rewrite-attempt",
                "workflowExecutionAttempt", attempt
            ))
            .build();
    }

    private Map<String, Object> diagnosticWorkflow(List<String> tools) {
        return Map.of(
            "enabled", true,
            "workflow", "database_diagnostic",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 8),
            "steps", List.of(
                Map.of("step", 1, "tool", tools.get(0), "required", true),
                Map.of("step", 2, "tool", tools.get(1), "required", true),
                Map.of("step", 3, "tool", tools.get(2), "required", true)
            )
        );
    }

    private ToolRuntimeRequest diagnosticAttemptRequest(String toolName,
                                                        Object workflowConfig,
                                                        List<String> allowedTools,
                                                        int attempt,
                                                        String assetId) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("interpretation_plan")
            .requestId("req-diagnostic-" + attempt + "-" + toolName)
            .conversationId("conv-diagnostic")
            .tenantId("tenant-1")
            .userId("user-diagnostic")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder()
                .userId("user-diagnostic")
                .parameters(Map.of("executionContext", Map.of("assetId", assetId)))
                .build())
            .attributes(Map.of(
                "mcpWorkflow", workflowConfig,
                "__agentRunId", "run-diagnostic",
                "workflowExecutionAttempt", attempt
            ))
            .build();
    }

    private ToolRuntimeRequest diagnosticAliasRequest(String toolName,
                                                      Object workflowConfig,
                                                      List<String> allowedTools,
                                                      int attempt,
                                                      Map<String, Object> parameters,
                                                      Map<String, Object> additionalAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("mcpWorkflow", workflowConfig);
        attributes.put("__agentRunId", "run-generated-alias");
        attributes.put("workflowExecutionAttempt", attempt);
        attributes.putAll(additionalAttributes);
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("interpretation_plan")
            .requestId("req-generated-alias-" + attempt)
            .conversationId("conv-generated-alias")
            .tenantId("tenant-generated-alias")
            .userId("user-generated-alias")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder()
                .userId("user-generated-alias")
                .parameters(parameters)
                .build())
            .attributes(attributes)
            .build();
    }

    private Map<String, Object> batchCall(String callId, String toolName, String templateCode) {
        return Map.of(
            "callId", callId,
            "toolName", toolName,
            "arguments", Map.of(
                "executionContext", Map.of(
                    "assetId", "asset-a",
                    "assetDisplayName", "Oracle DEV",
                    "assetToolName", "db_query_oracle_dev"
                ),
                "templateCode", templateCode
            )
        );
    }

    private ToolRuntimeRequest batchRequest(List<Map<String, Object>> calls,
                                            boolean stopOnFailure,
                                            Map<String, Object> attributes) {
        List<String> allowedTools = calls.stream()
            .map(call -> String.valueOf(call.get("toolName")))
            .distinct()
            .toList();
        return ToolRuntimeRequest.builder()
            .toolName("sql_query_execute")
            .runtimeMode("interpretation_plan")
            .requestId("req-batch")
            .conversationId("conv-batch")
            .tenantId("tenant-1")
            .userId("user-batch")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder()
                .userId("user-batch")
                .parameters(Map.of(
                    "batchId", "oracle-health",
                    "executionMode", "SEQUENTIAL",
                    "stopOnFailure", stopOnFailure,
                    "calls", calls
                ))
                .build())
            .attributes(attributes)
            .build();
    }

    private ToolRuntimeRequest documentSearchRequest(Map<String, Object> attributes) {
        return ToolRuntimeRequest.builder()
            .toolName("document_search")
            .runtimeMode("agent_chat")
            .requestId("req-document-search")
            .conversationId("conv-document-search")
            .tenantId("tenant-1")
            .userId("user-document-search")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder()
                .userId("user-document-search")
                .parameters(Map.of("query", "PushGateway Prometheus", "document_ids", List.of("doc-1")))
                .build())
            .attributes(attributes)
            .build();
    }
}
