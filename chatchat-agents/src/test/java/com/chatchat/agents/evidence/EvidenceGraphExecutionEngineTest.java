package com.chatchat.agents.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGraphExecutionEngineTest {

    private final EvidenceGraphExecutionEngine engine = new EvidenceGraphExecutionEngine();

    @Test
    void buildsTraceableSqlEvidencePath() {
        EvidenceChunk chunk = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("ads.sql", null, null, "file-1", "SQL"),
            "select account_id, data_date from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i where data_date = 20260101",
            0.91,
            Map.of("refId", "doc://file-1#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph graph = engine.build("query:sql", List.of(chunk));

        assertThat(graph.contractVersion()).isEqualTo("evidence_graph_v1");
        assertThat(graph.nodes()).containsKeys(
            "evidence:1:chunk",
            "evidence:1:sql_fragment",
            "evidence:1:sql_normalized",
            "evidence:1:sql_trusted"
        );
        assertThat(graph.edges())
            .extracting(EvidenceGraphEdge::type)
            .containsExactly(
                EvidenceGraphEdgeType.DERIVED_FROM,
                EvidenceGraphEdgeType.NORMALIZES_TO,
                EvidenceGraphEdgeType.VALIDATED_AS
            );
        assertThat(graph.sqlLineage()).containsExactly("gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
        assertThat(graph.validPaths()).hasSize(1);
        assertThat(graph.validPaths().get(0).nodeIds())
            .containsExactly(
                "evidence:1:chunk",
                "evidence:1:sql_fragment",
                "evidence:1:sql_normalized",
                "evidence:1:sql_trusted"
            );
        assertThat(graph.nodes().get("evidence:1:sql_trusted").metadata())
            .containsEntry("sqlType", "SELECT")
            .containsEntry("isComplete", true)
            .containsEntry("executionVerified", true);
        assertThat(graph.validPaths().get(0).hasTrustedSql()).isTrue();
        assertThat(graph.validPaths().get(0).executable()).isTrue();
    }

    @Test
    void linksSameDocumentChunksIntoExecutableEvidenceDag() {
        EvidenceChunk primary = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("phoenix-delete.md", null, null, "doc-delete", "Background"),
            "Phoenix delete operation should verify impact on hbase table and related cluster state.",
            0.92,
            Map.of("refId", "doc://doc-delete#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceChunk supporting = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("phoenix-delete.md", null, null, "doc-delete", "Steps"),
            "Delete validation requires creating APX.T_ZQYE_HIS_TEST and checking downstream impact before execution.",
            0.87,
            Map.of("refId", "doc://doc-delete#chunk=1", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph graph = engine.build("query:delete-impact", List.of(primary, supporting));
        EvidenceExecutionReport report = new EvidencePathExecutor().execute(graph);
        EvidenceExecutionContract contract = new EvidenceExecutionContractCompiler().compile(graph, report);

        assertThat(graph.edges())
            .extracting(EvidenceGraphEdge::type)
            .contains(EvidenceGraphEdgeType.EXPANDS, EvidenceGraphEdgeType.SUPPORTS);
        assertThat(graph.validPaths().get(0).nodeIds()).hasSizeGreaterThan(1);
        assertThat(report.decision()).isEqualTo(EvidenceExecutionDecision.ANSWER_ALLOWED);
        assertThat(contract.executionDag().edges()).hasSizeGreaterThan(0);
        assertThat(contract.executionSpec().steps()).hasSizeGreaterThan(1);
        assertThat(contract.evidence().direct())
            .extracting(EvidenceExecutionContract.EvidenceItem::refId)
            .contains("doc://doc-delete#chunk=0", "doc://doc-delete#chunk=1");
    }

    @Test
    void ranksWeightedLinkedEvidencePathAheadOfFlatSingleChunkPaths() {
        EvidenceChunk primary = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("migration.md", null, null, "doc-migration", "Overview"),
            "TiDB migration requires checking MySQL compatibility and operating system upgrade impact.",
            0.89,
            Map.of("refId", "doc://doc-migration#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceChunk expansion = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("migration.md", null, null, "doc-migration", "Compatibility"),
            "MySQL compatibility checks support the migration assessment and expand the same execution evidence path.",
            0.84,
            Map.of("refId", "doc://doc-migration#chunk=1", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph graph = engine.build("query:migration", List.of(primary, expansion));

        assertThat(graph.validPaths()).hasSize(1);
        assertThat(graph.validPaths().get(0).nodeIds()).hasSize(2);
        assertThat(graph.validPaths().get(0).score()).isGreaterThan(0.65);
        assertThat(graph.edges())
            .anySatisfy(edge -> {
                assertThat(edge.type()).isEqualTo(EvidenceGraphEdgeType.EXPANDS);
                assertThat(edge.weight()).isBetween(0.0, 1.0);
                assertThat(edge.reasoning()).contains("direction=source_sequence");
            });
    }

    @Test
    void createsContradictionEdgesAndPrunesConflictingEvidenceFromBestPath() {
        EvidenceChunk allowed = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("delete-impact.md", null, null, "doc-conflict", "Allowed"),
            "Phoenix delete operation can affect hbase table impact and requires validation before execution.",
            0.93,
            Map.of("refId", "doc://doc-conflict#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceChunk supporting = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("delete-impact.md", null, null, "doc-conflict", "Support"),
            "Validation impact requires checking hbase table state and downstream Phoenix delete operation results.",
            0.82,
            Map.of("refId", "doc://doc-conflict#chunk=1", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceChunk conflict = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("delete-impact.md", null, null, "doc-conflict", "Conflict"),
            "Phoenix delete operation does not affect hbase table impact and requires no validation.",
            0.81,
            Map.of("refId", "doc://doc-conflict#chunk=2", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph graph = engine.build("query:conflict", List.of(allowed, supporting, conflict));

        assertThat(graph.edges())
            .extracting(EvidenceGraphEdge::type)
            .contains(EvidenceGraphEdgeType.CONTRADICTS);
        assertThat(graph.validPaths().get(0).nodeIds())
            .contains("evidence:1:chunk", "evidence:2:chunk")
            .doesNotContain("evidence:3:chunk");
    }

    @Test
    void executesTrustedSqlPathUnderEvidenceOsV2Policy() {
        EvidenceChunk chunk = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("ads.sql", null, null, "file-1", "SQL"),
            "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i",
            0.95,
            Map.of("refId", "doc://file-1#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceExecutionReport report = new EvidencePathExecutor().execute(engine.build("query:sql", List.of(chunk)));

        assertThat(report.contractVersion()).isEqualTo("evidence_os_execution_v2");
        assertThat(report.decision()).isEqualTo(EvidenceExecutionDecision.ANSWER_ALLOWED);
        assertThat(report.answerContract().fromGraphOnly()).isTrue();
        assertThat(report.answerContract().executable()).isTrue();
        assertThat(report.answerContract().sqlLineage()).containsExactly("gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void compilesDeterministicExecutionContractFromSelectedPath() {
        EvidenceChunk chunk = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("ads.sql", null, null, "file-1", "SQL"),
            "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i",
            0.95,
            Map.of("refId", "doc://file-1#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceGraph graph = engine.build("query:sql", List.of(chunk));
        EvidenceExecutionReport report = new EvidencePathExecutor().execute(graph);

        EvidenceExecutionContract contract = new EvidenceExecutionContractCompiler().compile(graph, report);
        String compiledAnswer = new DeterministicAnswerCompiler().compile(contract);

        assertThat(contract.contractVersion()).isEqualTo("evidence_execution_contract_v2_2");
        assertThat(contract.decision()).isEqualTo(EvidenceExecutionDecision.ANSWER_ALLOWED);
        assertThat(contract.contractHash()).hasSize(64);
        assertThat(contract.graphViewHash()).hasSize(64);
        assertThat(contract.sourceRefs()).containsExactly("doc://file-1#chunk=0");
        assertThat(contract.trustedSql()).hasSize(1);
        assertThat(contract.trustedSql().get(0).normalizedSql())
            .contains("gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
        assertThat(contract.trustedSql().get(0).executionVerified()).isTrue();
        assertThat(contract.deterministicFacts())
            .extracting(EvidenceExecutionContract.DeterministicFact::sourceRef)
            .containsExactly("doc://file-1#chunk=0");
        assertThat(compiledAnswer)
            .contains("Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2)")
            .contains("---BEGIN_LOCKED_ANSWER---")
            .contains("---END_LOCKED_ANSWER---")
            .contains("\"type\" : \"evidence_reasoning_v2\"")
            .contains("Execution constraint: this answer uses only paths")
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void rejectsUnverifiedSqlPathUnderEvidenceOsV2Policy() {
        EvidenceChunk chunk = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("broken.sql", null, null, "file-1", "SQL"),
            "select from",
            0.95,
            Map.of("refId", "doc://file-1#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceExecutionReport report = new EvidencePathExecutor().execute(engine.build("query:sql", List.of(chunk)));

        assertThat(report.decision()).isEqualTo(EvidenceExecutionDecision.EMPTY_RESULT);
        assertThat(report.answerContract().fromGraphOnly()).isFalse();
        assertThat(report.reasons()).isNotEmpty();
    }

    @Test
    void keepsFullGraphImmutableAndFiltersOnlyProjectedView() {
        EvidenceChunk allowed = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("allowed.docx", null, null, "doc-allowed", "Body"),
            "visible document evidence",
            0.8,
            Map.of("refId", "doc://doc-allowed#chunk=0"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
        EvidenceChunk blocked = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("blocked.docx", null, null, "doc-blocked", "Body"),
            "blocked document evidence",
            0.99,
            Map.of("refId", "doc://doc-blocked#chunk=0"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph fullGraph = engine.build(
            "query:visibility",
            List.of(allowed, blocked),
            DocumentSelectionContext.of(List.of("doc-allowed"), true)
        );
        EvidenceGraph view = new EvidenceGraphView().project(
            fullGraph,
            DocumentSelectionContext.of(List.of("doc-allowed"), true)
        );

        assertThat(fullGraph.nodes().values())
            .extracting(EvidenceGraphNode::rawContent)
            .contains("visible document evidence", "blocked document evidence");
        assertThat(view.nodes().values())
            .extracting(EvidenceGraphNode::rawContent)
            .contains("visible document evidence")
            .doesNotContain("blocked document evidence");
    }

    @Test
    void rejectsPathThatEscapesDocumentVisibilitySet() {
        EvidenceChunk blocked = new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource("blocked.sql", null, null, "doc-blocked", "SQL"),
            "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i",
            0.95,
            Map.of("refId", "doc://doc-blocked#chunk=0", "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );

        EvidenceGraph graph = engine.build("query:sql", List.of(blocked));
        EvidenceExecutionReport report = new EvidencePathExecutor().execute(
            graph,
            EvidenceOsPolicy.productionDefault(),
            DocumentSelectionContext.of(List.of("doc-allowed"), true)
        );

        assertThat(report.decision()).isEqualTo(EvidenceExecutionDecision.EMPTY_RESULT);
        assertThat(report.reasons()).contains("selected path contains document outside visibility set");
    }
}
