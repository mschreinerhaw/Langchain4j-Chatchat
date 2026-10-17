package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodingValidationPlanServiceTest {
    @Test void independentDecimalVerifierDetectsPrecisionErrorAndDuplicateSource() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:coding-plan-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), mapper,
            new DataSourceTransactionManager(source));
        var python = mock(McpPythonControlPlanePort.class);
        final byte[][] content = new byte[1][];
        when(python.uploadDataFile(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenAnswer(call -> {
                content[0] = call.getArgument(5);
                return new McpPythonControlPlanePort.DataFileResult(call.getArgument(2), "stored",
                    "mounted", content[0].length, call.getArgument(4), "AVAILABLE");
            });
        when(python.downloadDataFile(anyString(), anyString(), anyString()))
            .thenAnswer(call -> content[0]);
        var access = new CodingInvocationContext();
        var snapshots = new CodingInputSnapshotService(evidence, python, access, mapper);
        var plans = new CodingValidationPlanService(evidence, snapshots, access, mapper,
            List.of(new FixtureDecimalCodingVerifier(python, mapper), new CodingSchemaResultVerifier()));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var delivered = new Dataset("template#call", Map.of(), List.of(
            Map.of("tradeId", "t1", "amount", "0.10"),
            Map.of("tradeId", "t2", "amount", "0.20")));
        var input = access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            snapshots.capture(scope, "task", List.of(delivered), "template#call", "decision:1",
                "workspace:1", 10, 4096));
        var plan = access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            plans.register(scope, "task", input,
                FixtureDecimalCodingVerifier.plan("amount", "total", "tradeId", false)));
        var contract = contract(scope, input, plan);
        var validator = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () ->
            plans.validator(contract));
        assertThat(validator.validate(List.of(Map.of("total", "0.30"))).passed()).isTrue();
        assertThat(validator.validate(List.of(Map.of("total", "0.30000000000000004"))).passed()).isFalse();
        assertThat(validator.validate(List.of()).passed()).isFalse();
        var structural = access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            plans.register(scope, "task", input, new CodingValidationPlanService.Plan("runtime-result-schema", "v1",
                Map.of("rowSchema", Map.of("type", "object", "properties", Map.of("label", Map.of("type", "string")),
                    "required", List.of("label"), "additionalProperties", false)))));
        var shapeCheck = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () -> plans.validator(contract(scope,input,structural)));
        var checked = shapeCheck.validate(List.of(Map.of("label", "an arbitrary model conclusion")));
        assertThat(checked.passed()).isTrue();
        assertThat(checked.businessEvaluated()).isFalse();
        assertThat(checked.status()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        assertThat(shapeCheck.validate(List.of(Map.of("label", 12))).status()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        assertThatThrownBy(() -> access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            plans.register(scope,"task",input,new CodingValidationPlanService.Plan("untrusted-model-verifier","v1",Map.of()))))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> access.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            plans.register(scope, "another-task", input,
                FixtureDecimalCodingVerifier.plan("amount", "total", null, false))))
            .isInstanceOf(SecurityException.class);
        content[0] = "{\"tradeId\":\"t1\",\"amount\":\"9\"}\n".getBytes();
        assertThatThrownBy(() -> validator.validate(List.of(Map.of("total", "9"))))
            .isInstanceOf(SecurityException.class);
    }

    private static CodingTaskContract contract(KernelDataScope scope,
            CodingInputSnapshotService.Snapshot input, String plan) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("calculate", List.of("result"), List.of(plan)),
            input.reference(), List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of("python_execute"),
                List.of(input.fileId()), List.of(), List.of(), CodingTaskContract.NetworkPolicy.NONE,
                List.of(), List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(180)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                1, 2048, 1, 1, "1", "USD", 1000, 256 * 1024 * 1024, 10000),
            "env:1", plan,
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                100, 10000, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
