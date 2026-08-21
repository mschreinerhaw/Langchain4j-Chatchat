package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PythonAnalysisBridgeTest {
    @Test
    void discoveryAndBindingDoNotExecuteBeforeRuntimeGatewayCall() {
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        PythonDataFileService dataFiles = mock(PythonDataFileService.class);
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        PythonCapabilityService service = mock(PythonCapabilityService.class);
        PythonTemplate template = template("template-log", "asset-log", "env-python", "log_analysis.py");
        template.setInputSchemaJson("""
            {"type":"object","properties":{"source_file":{"type":"FILE"},"limit":{"type":"integer","default":100}}}
            """);
        when(templates.findByTenantIdAndStatus("tenant-a", "PUBLISHED")).thenReturn(List.of(template));
        when(templates.findByIdAndTenantId("template-log", "tenant-a"))
            .thenReturn(java.util.Optional.of(template));
        when(dataFiles.discover("tenant-a", "alice", "1786342932178.log", 20)).thenReturn(List.of(
            new PythonDataFileService.DataFileView("file-42", "1786342932178.log", 128L, 1000L)));
        when(services.getObject()).thenReturn(service);
        PythonExecution execution = new PythonExecution();
        execution.setId("execution-1");
        execution.setStatus("SUCCEEDED");
        execution.setExitCode(0);
        execution.setStdout("{\"status\":\"SUCCESS\"}");
        execution.setDurationMs(321L);
        when(service.executeTemplateForUser("template-log", "tenant-a", "alice",
            Map.of("source_file", "file-42", "limit", 100))).thenReturn(execution);
        ObjectMapper mapper = new ObjectMapper();
        PythonAnalysisBridge bridge = new PythonAnalysisBridge(templates, services,
            new PythonTemplateArgumentResolver(mapper), dataFiles, mapper);

        PythonAnalysisBridge.Result discovery = bridge.run(Map.of(
            "tenantId", "tenant-a",
            "query", "帮我运行 log_analysis.py 并分析 1786342932178.log 日志文件",
            "script", "log_analysis.py"));
        assertThat(discovery.body()).containsEntry("status", "CANDIDATES_FOUND")
            .containsEntry("executionTool", PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL);

        PythonAnalysisBridge.Result prepared = bridge.run(Map.of(
            "tenantId", "tenant-a", "username", "alice", "templateId", "template-log",
            "file", "1786342932178.log", "parameters", Map.of()));
        assertThat(prepared.body()).containsEntry("status", "READY_FOR_EXECUTION");
        assertThat(prepared.body().get("executionArguments").toString()).contains("file-42");

        PythonAnalysisBridge.Result result = bridge.execute(Map.of(
            "tenantId", "tenant-a", "username", "alice", "templateId", "template-log",
            "parameters", Map.of("source_file", "1786342932178.log")));

        assertThat(result.error()).isFalse();
        assertThat(result.body()).containsEntry("status", "SUCCEEDED")
            .containsEntry("templateId", "template-log")
            .containsEntry("assetId", "asset-log")
            .containsEntry("environmentId", "env-python")
            .containsEntry("bridgeManaged", true);
    }

    @Test
    void returnsChoicesInsteadOfGuessingWhenTwoEnvironmentsTie() {
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        PythonTemplate first = template("template-1", "asset-1", "env-1", "analysis.py");
        PythonTemplate second = template("template-2", "asset-2", "env-2", "analysis.py");
        when(templates.findByTenantIdAndStatus("tenant-a", "PUBLISHED")).thenReturn(List.of(first, second));
        ObjectMapper mapper = new ObjectMapper();
        PythonAnalysisBridge bridge = new PythonAnalysisBridge(templates, services,
            new PythonTemplateArgumentResolver(mapper), mock(PythonDataFileService.class), mapper);

        PythonAnalysisBridge.Result result = bridge.run(Map.of(
            "tenantId", "tenant-a", "username", "alice", "script", "analysis.py"));

        assertThat(result.error()).isFalse();
        assertThat(result.body()).containsEntry("status", "CANDIDATES_FOUND")
            .containsEntry("requiresModelReview", true);
        assertThat((List<?>) result.body().get("candidates")).hasSize(2);
    }

    private PythonTemplate template(String id, String assetId, String environmentId, String script) {
        PythonTemplate template = new PythonTemplate();
        template.setId(id);
        template.setTenantId("tenant-a");
        template.setOwnerId("alice");
        template.setAssetId(assetId);
        template.setAssetName(assetId);
        template.setEnvironmentId(environmentId);
        template.setTemplateName(script);
        template.setScriptFileName(script);
        template.setScenario("日志分析");
        template.setDescription("日志分析");
        template.setStatus("PUBLISHED");
        template.setInputSchemaJson("{}");
        return template;
    }
}
