package com.chatchat.api.runtime.coding.cline;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class ClineModelReceiptOrderingTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void persistedNodeOrderingPreservesNewAndLegacyProofsWithoutAcceptingOtherIdentities(boolean legacy) throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:receipt-order-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules();
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json, new DataSourceTransactionManager(source));
        var receipts = new ClineModelReceiptStore(evidence);
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var contract = ClineModelCallbackControllerTest.contract(scope);
        var input = Map.<String,Object>of("authorized", true);
        evidence.register(new EvidenceRecord(null, "input", scope, "CODING_INPUT_SNAPSHOT", "test",
            evidence.contentHash(input),input,null,1,Map.of()),
            new EvidenceLineage(null,"input",scope,List.of(),"task","test",null,Map.of()));
        var parameters = json.readTree("{\"z\":1,\"a\":{\"y\":2,\"b\":3}}");
        var response = Map.<String,Object>of("text","", "usage",Map.of("inputTokens",1,"outputTokens",2),
            "toolCalls",List.of(Map.of("id","call", "name","python_execute", "input",parameters)));
        String proof = receipts.proofRef(scope,"task","model-1");
        if (legacy) {
            var payload = Map.<String,Object>of("taskId","task","operationId","model-1",
                "executionHandle","runtime-model:model-1","contractHash",CodingCanonicalHash.sha256(contract),"response",response);
            String id = proof.substring("runtime-evidence:".length());
            evidence.register(new EvidenceRecord(null,id,scope,"CODING_MODEL_RESPONSE_RECEIPT","test",
                evidence.contentHash(payload),payload,null,1,Map.of()),
                new EvidenceLineage(null,id,scope,List.of("input"),"task","test","model-1",Map.of()));
            assertThat(evidence.contentHash(evidence.find(scope,id).orElseThrow().payload())).isNotEqualTo(evidence.contentHash(payload));
        } else receipts.commit(contract,"model-1","runtime-model:model-1",response);
        assertThat(receipts.confirmsStopped(scope,"task","model-1","runtime-model:model-1",proof)).isTrue();
        assertThat(receipts.telemetry(contract,"model-1",1)).isPresent();
        assertThat(receipts.confirmsStopped(scope,"other","model-1","runtime-model:model-1",proof)).isFalse();
        assertThat(receipts.confirmsStopped(scope,"task","model-1","runtime-model:model-2",proof)).isFalse();
        assertThat(receipts.confirmsStopped(scope,"task","model-1","runtime-model:model-1",proof+"altered")).isFalse();
    }
}
