package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2-backed owner transactions; the MCP port supplies archived bytes for collector isolation tests. */
class CodingEvidenceCollectorTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final KernelDataScope scope = new KernelDataScope("tenant", "owner", "trace", null, "run", null, Map.of());
    DriverManagerDataSource dataSource;
    JdbcRuntimeEvidenceStore store;
    DurableCodingTaskService tasks;
    McpPythonControlPlanePort python;
    ExecutorService workers;
    ScheduledExecutorService timer;
    CodingTaskContract contract;

    @BeforeEach void setup(TestInfo test) throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:c-evidence-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(dataSource);
        store = reopen();
        workers = Executors.newSingleThreadExecutor(); timer = Executors.newSingleThreadScheduledExecutor();
        tasks = new DurableCodingTaskService(store, json, Clock.systemUTC(),
            new BoundCodingTaskAccess(() -> scope, EnumSet.allOf(CodingTaskAccess.Action.class)),
            (owner, ref) -> store.find(owner, ref.startsWith("runtime-evidence:") ? ref.substring(17) : ref).isPresent()
                    || store.snapshot(owner, ref.startsWith("runtime-evidence:") ? ref.substring(17) : ref).isPresent(),
            (owner, taskId, operationId, handle, proofRef) -> true,
            workers, new ConcurrentTaskScheduler(timer));
        python = mock(McpPythonControlPlanePort.class);
        doAnswer(call -> {
            ((java.io.OutputStream)call.getArgument(7)).write(python.artifact(call.getArgument(0),call.getArgument(1),
                call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6)));
            return null;
        }).when(python).downloadArtifact(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(java.io.OutputStream.class));
        contract = new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("Reconcile prices", List.of("rows.jsonl"), List.of("criteria:fixed")),
            "runtime-evidence:input-prices", List.of(), "workspace:task",
            new CodingTaskContract.Authorization("decision:prices", List.of("python.run"), List.of("prices"),
                List.of("prices"), List.of("rows.jsonl"), CodingTaskContract.NetworkPolicy.NONE,
                List.of(), List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget", Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 0, 0, 10, 1,
                "10", "USD", 10000, 1024 * 1024, 1024 * 1024), "env", "validation:fixed",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:amount", "schema:validation", List.of("rows.jsonl"),
                List.of("application/x-ndjson"), 100, 10000, List.of(CodingTaskContract.RequiredEvidence.values())),
            test.getTags().contains("visual-report")?Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","reportMode","visual"):Map.of());
        tasks.submit(contract);
    }
    @AfterEach void shutdown() { timer.shutdownNow(); workers.shutdownNow(); }
    @Test @Tag("visual-report") void visualReportEvidenceRemainsReplayableAcrossFinalCollectionAndRestart()throws Exception{
        var report=Map.of("schemaVersion","python_visual_report.v1","title","Replayed report","status","SUCCESS",
            "dataSources",List.of("prices"),"validation",Map.of("checks",List.of(Map.of("name","computed","status","PASSED"))));
        fixture(json.writeValueAsString(Map.of("visualReport",report,"reportMarkdown","Valid text","evidenceReferences",List.of("prices")))+"\n","PRESENT",true,
            "{\"evidenceReferences\":\"ARRAY\",\"reportMarkdown\":\"STRING\",\"visualReport\":\"OBJECT\"}",1);
        var firstCollector=collector(store);firstCollector.setVisualReports(new VisualReportArtifacts(json));
        var files=List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n")));
        var first=firstCollector.collect(scope,contract,"op","main.py","rows.jsonl",files,rows->new CodingEvidenceCollector.Validation(true,Map.of()));
        var resumed=collector(reopen());resumed.setVisualReports(new VisualReportArtifacts(json));
        var replay=resumed.collect(scope,contract,"op","main.py","rows.jsonl",files,rows->new CodingEvidenceCollector.Validation(true,Map.of()));
        assertThat(replay).isEqualTo(first);
        assertThat(resumed.visualReport(scope,"task",replay.resultDatasetRef())).contains(firstCollector.visualReport(scope,"task",first.resultDatasetRef()).orElseThrow());
        var original=resumed.visualReport(scope,"task",replay.resultDatasetRef()).orElseThrow();
        assertThat(resumed.reviewVisualReport(scope,"task",replay.resultDatasetRef())).isEqualTo(original);
        assertThat(resumed.visualReport(scope,"task",replay.resultDatasetRef())).contains(original);
        when(python.artifact("tenant","owner","run","task","op","manifest","manifest.json")).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->resumed.reviewVisualReport(scope,"task",replay.resultDatasetRef())).hasMessageContaining("manifest changed");
    }
    @Test void aSchemaValidCopyOfArchivedSourceCannotBecomeAnAdmittedAnalysis() throws Exception {
        String source = "private diagnostic source\n".repeat(100);
        String result = json.writeValueAsString(Map.of("finding",source))+"\n";
        fixture(result,"PRESENT",true,"{\"finding\":\"STRING\"}",1);
        when(python.artifact("tenant","owner","run","task","op","input-prices","prices.csv"))
            .thenReturn(source.getBytes(StandardCharsets.UTF_8));
        var rules=Map.<String,Object>of("rowSchema",Map.of("type","object","properties",Map.of("finding",Map.of("type","string"))));
        var collected=collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha(source))),
            rows->new CodingSchemaResultVerifier().verify(scope,null,rules,rows));
        assertThat(collected.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        assertThat(collector(store).copiedSourceResult(scope,"task",collected.validationRecordRef())).isTrue();
        assertThatThrownBy(() -> collector(store).copiedSourceResult(scope,"task",collected.resultDatasetRef()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(scope,id(collected.validationRecordRef())).orElseThrow().payload().toString())
            .contains("copiedSourceContentDetected=true").doesNotContain("private diagnostic source");
    }
    private Map<String,Object> optionalAmountRules() {
        return Map.of("rowSchema",Map.of("type","object","properties",Map.of(
            "amount",Map.of("type","number"),"note",Map.of("type","string")),
            "required",List.of("note"),"additionalProperties",false));
    }
    @Test void optionalColumnsAreAdmittedUnderTheirDeclaredOutputContract() throws Exception {
        fixture("{\"amount\":12.5,\"note\":\"observed\"}\n{\"note\":\"not applicable\"}\n","PRESENT",true,
            "{\"amount\":\"MISSING|NUMBER\",\"note\":\"STRING\"}",2);
        var result=collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n"))),
            rows->new CodingSchemaResultVerifier().verify(scope,null,optionalAmountRules(),rows));
        assertThat(result.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        assertThat(collector(store).copiedSourceResult(scope,"task",result.validationRecordRef())).isFalse();
        try(var dataset=collector(reopen()).openResult(scope,"task",result.resultDatasetRef())) {
            assertThat(dataset.recordCount()).isEqualTo(2);
            assertThat(dataset.readPage(0,2).rows().get(1)).doesNotContainKey("amount");
        }
    }
    @Test void observedMixedTypesDoNotBypassTheTrustedOutputContract() throws Exception {
        fixture("{\"amount\":12.5,\"note\":\"observed\"}\n{\"amount\":\"invalid\",\"note\":\"wrong type\"}\n","PRESENT",true,
            "{\"amount\":\"NUMBER|STRING\",\"note\":\"STRING\"}",2);
        var result=collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n"))),
            rows->new CodingSchemaResultVerifier().verify(scope,null,optionalAmountRules(),rows));
        assertThat(result.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
    }
    @Test void trailingJsonValuesCannotBeAdmittedByAValidManifestCount() throws Exception {
        fixture("{\"amount\":1} {\"amount\":2}\n","PRESENT");
        assertThatThrownBy(()->collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n"))),
            rows->new CodingEvidenceCollector.Validation(true,Map.of())))
            .isInstanceOf(CodingEvidenceCollector.InvalidStructuredResultException.class);
    }
    JdbcRuntimeEvidenceStore reopen() {
        return new JdbcRuntimeEvidenceStore(new JdbcTemplate(dataSource), json, new DataSourceTransactionManager(dataSource));
    }

    @Test void sixReferencesResolveWithAuthorizedDatasetAndLineageAfterRestart() throws Exception {
        fixture("{\"amount\":12.50}\n", "PRESENT");
        var collector = collector(store);
        var result = collector.collect(scope, contract, "op", "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n"))),
            rows -> new CodingEvidenceCollector.Validation(rows.size() == 1
                && new java.math.BigDecimal(rows.get(0).get("amount").toString()).compareTo(new java.math.BigDecimal("12.50")) == 0,
                Map.of("independentExpectedAmount", "12.50")));
        assertThat(result.businessValidationPassed()).isTrue();
        KernelDataScope recoveredRequest = new KernelDataScope("tenant", "owner", "new-http-trace", null, "run", null, Map.of());
        assertThat(collector(reopen()).collect(recoveredRequest, contract, "op", "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n"))),
            rows -> new CodingEvidenceCollector.Validation(true, Map.of("independentExpectedAmount", "12.50"))))
            .isEqualTo(result);
        for (String ref : List.of(result.inputSnapshotRef(), result.codeArtifactRef(), result.executionReceiptRef(),
                result.resultDatasetRef(), result.validationRecordRef()))
            assertThat(reopen().find(scope, id(ref))).isPresent();
        assertThat(reopen().snapshot(scope, id(result.evidenceBundleRef())).orElseThrow().evidenceIds()).hasSize(5);
        assertThat(reopen().lineage(scope, id(result.resultDatasetRef())).orElseThrow().parentEvidenceIds())
            .contains(id(result.inputSnapshotRef()), id(result.codeArtifactRef()), id(result.executionReceiptRef()));
        try (var dataset = collector(reopen()).openResult(scope, "task", result.resultDatasetRef())) {
            assertThat(dataset.recordCount()).isEqualTo(1);
            assertThat(dataset.readPage(0, 1).rows().get(0).get("amount").toString()).isEqualTo("12.5");
        }
        try (var registered = collector(reopen()).registeredResult(scope, "task", result.resultDatasetRef())) {
            assertThat(registered.recordCount()).isEqualTo(1);
            assertThat(registered.analysisContext().get("parentEvidenceIds")).asList().hasSize(3);
        }
        KernelDataScope other = new KernelDataScope("other", "owner", "trace", null, "run", null, Map.of());
        assertThatThrownBy(() -> collector(reopen()).collect(other, contract, "op", "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n"))),
            rows -> new CodingEvidenceCollector.Validation(true, Map.of())))
            .isInstanceOf(SecurityException.class);
        assertThat(reopen().find(other, id(result.resultDatasetRef()))).isEmpty();
        assertThatThrownBy(() -> collector(reopen()).openResult(other, "task", result.resultDatasetRef()))
            .isInstanceOf(RuntimeException.class);
    }

    @Test void emptyDatasetIsValidButBusinessFailureStaysAValidationFact() throws Exception {
        fixture("", "EMPTY");
        var result = collector(store).collect(scope, contract, "op", "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n"))),
            rows -> new CodingEvidenceCollector.Validation(false, Map.of("expectedNonEmpty", true)));
        assertThat(result.businessValidationPassed()).isFalse();
        assertThat(reopen().find(scope, id(result.validationRecordRef())).orElseThrow().payload().get("status"))
            .isEqualTo("FAILED");
        assertThat(collector(reopen()).openResult(scope, "task", result.resultDatasetRef()).recordCount()).isZero();
    }

    @Test void incompleteResultNeverPublishesPublicReferences() throws Exception {
        fixture("{\"amount\":12.50}\n", "PARTIAL");
        assertThatThrownBy(() -> collector(store).collect(scope, contract, "op", "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n"))),
            rows -> new CodingEvidenceCollector.Validation(true, Map.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
            "select count(*) from runtime_evidence_entry where entry_kind='EVIDENCE'", Integer.class)).isZero();
    }

    @Test void auxiliaryMetadataDoesNotBecomeAnalysisRowsOrInvalidateVerifiedRequiredJsonl() throws Exception {
        fixture("{\"amount\":12.50}\n","PRESENT");
        var operation=python.get("tenant","owner","run","task","op");
        var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(
            python.artifact("tenant","owner","run","task","op","manifest","manifest.json"));
        String auxiliary="{\"debug\":true}";
        ((com.fasterxml.jackson.databind.node.ArrayNode)manifest.path("artifacts")).add(json.valueToTree(Map.of(
            "channel","results","fileName","_summary.json","size",auxiliary.length(),"sha256",sha(auxiliary),
            "rows",-1,"completeness","UNVERIFIED")));
        manifest.put("resultCompleteness","UNVERIFIED");String encoded=json.writeValueAsString(manifest);
        when(python.artifact("tenant","owner","run","task","op","manifest","manifest.json"))
            .thenReturn(encoded.getBytes(StandardCharsets.UTF_8));
        when(python.artifact("tenant","owner","run","task","op","results","_summary.json"))
            .thenReturn(auxiliary.getBytes(StandardCharsets.UTF_8));
        when(python.get("tenant","owner","run","task","op"))
            .thenReturn(new McpPythonControlPlanePort.OperationView("op","SUCCEEDED","container",1,false,true,0,
                operation.codeHash(),"sha256:image",sha(encoded),null));
        var result=collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n"))),
            rows->new CodingEvidenceCollector.Validation(true,Map.of()));
        try(var dataset=collector(store).openResult(scope,"task",result.resultDatasetRef())) {
            assertThat(dataset.recordCount()).isEqualTo(1);
            assertThat(dataset.readPage(0,1).rows().get(0)).containsKey("amount").doesNotContainKey("debug");
        }
    }

    @Test void archiveCommitFailureLeavesNoDanglingReferenceAndRestartCommitsWithoutRerun() throws Exception {
        fixture("{\"amount\":12.50}\n", "PRESENT");
        JdbcRuntimeEvidenceStore failed = spy(reopen());
        doThrow(new IllegalStateException("injected archive transaction failure"))
            .when(failed).commitCodingEvidence(any(), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyString(), anyList(), anyMap());
        CodingEvidenceCollector.IndependentValidator check = rows ->
            new CodingEvidenceCollector.Validation(true, Map.of("expected", "12.50"));
        List<CodingEvidenceCollector.InputFile> inputs = List.of(
            new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha("amount\n12.50\n")));
        assertThatThrownBy(() -> collector(failed).collect(scope, contract, "op", "main.py", "rows.jsonl", inputs, check))
            .hasMessageContaining("injected archive transaction failure");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
            "select count(*) from runtime_evidence_entry where entry_kind='EVIDENCE'", Integer.class)).isZero();
        var resumed = collector(reopen()).collect(scope, contract, "op", "main.py", "rows.jsonl", inputs, check);
        assertThat(reopen().snapshot(scope, id(resumed.evidenceBundleRef()))).isPresent();
        verify(python, never()).start(any());
    }

    @Test void verifiedArchiveWithoutRequiredOutputHasADistinctNonCommittingOutcome() throws Exception {
        fixture("", "EMPTY", false);
        assertThatThrownBy(()->collector(store).collect(scope,contract,"op","main.py","rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices","prices.csv",sha("amount\n12.50\n"))),
            rows->new CodingEvidenceCollector.Validation(true,Map.of())))
            .isInstanceOf(CodingEvidenceCollector.RequiredOutputMissingException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
            "select count(*) from runtime_evidence_entry where entry_kind='EVIDENCE'",Integer.class)).isZero();
        verify(python,never()).start(any());
    }

    CodingEvidenceCollector collector(JdbcRuntimeEvidenceStore database) {
        return new CodingEvidenceCollector(database, database, tasks, python, json);
    }
    void fixture(String result, String completeness) throws Exception {
        fixture(result,completeness,true);
    }
    void fixture(String result, String completeness, boolean publishResult) throws Exception {
        fixture(result,completeness,publishResult,result.isBlank()?null:"{\"amount\":\"NUMBER\"}",result.isBlank()?0:1);
    }
    void fixture(String result,String completeness,boolean publishResult,String schema,long rowCount) throws Exception {
        String code = "print('fixed')\n", input = "amount\n12.50\n";
        String receipt = json.writeValueAsString(Map.of("operationId", "op", "stopConfirmed", true,
            "fencingToken", 1, "startedAt", "2026-10-15T01:00:00Z", "containerName", "container",
            "imageDigest", "sha256:image"));
        Map<String, Object> resultEntry = new LinkedHashMap<>();
        resultEntry.put("channel", "results"); resultEntry.put("fileName", "rows.jsonl");
        resultEntry.put("size", result.getBytes(StandardCharsets.UTF_8).length);
        resultEntry.put("sha256", sha(result)); resultEntry.put("rows", rowCount);
        resultEntry.put("completeness", completeness); resultEntry.put("schemaJson", schema);
        Map<String, Object> receiptEntry = new LinkedHashMap<>();
        receiptEntry.put("channel", "logs"); receiptEntry.put("fileName", "receipt.json");
        receiptEntry.put("size", receipt.getBytes(StandardCharsets.UTF_8).length);
        receiptEntry.put("sha256", sha(receipt)); receiptEntry.put("rows", -1);
        receiptEntry.put("completeness", "UNVERIFIED"); receiptEntry.put("schemaJson", null);
        String manifest = json.writeValueAsString(Map.of("artifacts", publishResult?List.of(resultEntry, receiptEntry):List.of(receiptEntry),
            "resultCompleteness", completeness));
        when(python.get("tenant", "owner", "run", "task", "op"))
            .thenReturn(new McpPythonControlPlanePort.OperationView("op", "SUCCEEDED", "container", 1,
                false, true, 0, sha(code), "sha256:image", sha(manifest), null));
        when(python.artifact("tenant", "owner", "run", "task", "op", "manifest", "manifest.json"))
            .thenReturn(manifest.getBytes(StandardCharsets.UTF_8));
        when(python.artifact("tenant", "owner", "run", "task", "op", "code", "main.py"))
            .thenReturn(code.getBytes(StandardCharsets.UTF_8));
        when(python.artifact("tenant", "owner", "run", "task", "op", "results", "rows.jsonl"))
            .thenReturn(result.getBytes(StandardCharsets.UTF_8));
        when(python.artifact("tenant", "owner", "run", "task", "op", "logs", "receipt.json"))
            .thenReturn(receipt.getBytes(StandardCharsets.UTF_8));
        when(python.artifact("tenant", "owner", "run", "task", "op", "input-prices", "prices.csv"))
            .thenReturn(input.getBytes(StandardCharsets.UTF_8));
        CodingJournal.Lease lease = tasks.claim(scope, "task", "worker", Duration.ofSeconds(60),
            tasks.get(scope, "task").revision());
        var reserved = tasks.reserve(scope, "task", lease, tasks.get(scope, "task").revision(),
            "op", sha("intent"), new CodingTaskOutcome.Counters(0, 0, 0, 1, 0, 0, 100, "0"));
        assertThat(reserved.admitted()).isTrue();
        tasks.startIntent(scope, "task", lease, reserved.outcome().revision(), "op", "execution:fixture");
        tasks.reconcileStopped(scope, "task", lease, tasks.get(scope, "task").revision(),
            "op", "mcp-python-receipt:" + sha(receipt));
    }
    static String id(String ref) { return ref.startsWith("runtime-evidence:") ? ref.substring(17) : ref; }
    static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
