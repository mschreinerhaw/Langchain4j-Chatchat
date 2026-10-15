package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingProtocol;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ClineModelReceiptStoreTest {
    @Test void durableReceiptProvesOnlyTheBoundModelOperation() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:model-receipt-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(data);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(data), new ObjectMapper(),
            new DataSourceTransactionManager(data));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var payload = Map.<String, Object>of("fixture", "authorized input");
        evidence.register(new EvidenceRecord(null, "input", scope, "CODING_INPUT_SNAPSHOT", "test",
            evidence.contentHash(payload), payload, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, "input", scope, List.of(), "task", "test", null, Map.of()));
        var contract = contract(scope);
        var receipts = new ClineModelReceiptStore(evidence);
        Map<String, Object> response = Map.of("text", "calculate", "toolCalls", List.of(),
            "usage", Map.of("inputTokens", 4, "outputTokens", 2), "requestId", "provider-request");
        String ref = receipts.commit(contract, "model-1", "runtime-model:model-1", response);
        assertThat(receipts.response(scope, "task", "model-1")).contains(response);
        assertThat(receipts.confirmsStopped(scope, "task", "model-1", "runtime-model:model-1", ref)).isTrue();
        assertThat(new ClineModelReceiptStore(new JdbcRuntimeEvidenceStore(new JdbcTemplate(data),
            new ObjectMapper(), new DataSourceTransactionManager(data)))
            .confirmsStopped(scope, "task", "model-1", "runtime-model:model-1", ref)).isTrue();
        assertThat(receipts.confirmsStopped(scope, "task", "model-1", "runtime-model:other", ref)).isFalse();
        assertThat(receipts.confirmsStopped(new KernelDataScope("other", "owner", null, null, "run", null, Map.of()),
            "task", "model-1", "runtime-model:model-1", ref)).isFalse();
    }

    private static CodingTaskContract contract(KernelDataScope scope) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("calculate", List.of("report"), List.of("validation:1")),
            "runtime-evidence:input", List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of(), List.of(), List.of(), List.of(),
                CodingTaskContract.NetworkPolicy.NONE, List.of(), List.of()),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(180).truncatedTo(ChronoUnit.MILLIS),
                1, 2048, 0, 0, "1", "USD", 0, 0, 4096), "env:1", "validation:1",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                1, 4096, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
