package com.chatchat.agents.orchestration;

import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolObservationBuilderEvidenceTest {

    private final ToolObservationBuilder builder = new ToolObservationBuilder(new EvidenceTrustEvaluator());

    @Test
    void includesUnifiedEvidenceContextForDocumentSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 3,
                "content", "restart the service after changing config"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("evidenceId: evidence:1")
            .contains("rawContent:")
            .contains("normalizedContent:")
            .contains("type: DOCUMENT")
            .contains("citation: doc://file-1#chunk=3")
            .contains("Evidence audit: toolName=document_search");
    }

    @Test
    void documentSearchObservationCountsTitleOnlyDocumentHits() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "query", "服务器清单",
            "total", 2,
            "results", List.of(),
            "documents", List.of(
                Map.of(
                    "docId", "doc-server-1",
                    "title", "数据资产管理平台服务器清单-推荐配置及软件部署清单 - 国都-20251031",
                    "fileName", "server-list-1.xlsx",
                    "tags", List.of("平台服务器清单")
                ),
                Map.of(
                    "docId", "doc-server-2",
                    "title", "LiveData服务器清单-推荐配置",
                    "fileName", "server-list-2.xlsx"
                )
            ),
            "retrievalSemantics", Map.of(
                "dataSafetyLevel", "NO_EVIDENCE_BODY",
                "canAnswerDirectly", false
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Document search summary: total=2, contentEvidence=0, documentHits=2, returned=2")
            .contains("数据资产管理平台服务器清单-推荐配置及软件部署清单")
            .contains("LiveData服务器清单-推荐配置")
            .contains("文档命中但未返回正文片段")
            .doesNotContain("returned=0");
    }

    @Test
    void includesUnifiedEvidenceContextForWebSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "web_evidence_v1",
            "results", List.of(Map.of(
                "title", "Example",
                "url", "https://example.com/a",
                "snippet", "external verification"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("web_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("type: WEB")
            .contains("citation: web://example.com/a#result=1")
            .contains("Web search summary");
    }

    @Test
    void includesUnifiedEvidenceContextForDocumentExpandEvidenceChunks() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "evidenceChunks", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 4,
                "text", "threshold is 1bp",
                "evidenceGrade", "A"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("citation: doc://file-1#chunk=4")
            .contains("sourceRef: doc://file-1#chunk=4")
            .contains("evidenceGrade: A")
            .contains("threshold is 1bp");
    }

    @Test
    void modelEvidenceEvaluationFiltersDocumentEvidenceBeforeGraph() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(
                Map.of(
                    "fileId", "livedata-report",
                    "fileName", "基于livedata数据编织的报表开发.docx",
                    "chunkIndex", 0,
                    "content", "基于livedata数据编织的报表开发，说明如何维护分析数据源、开发报表SQL、保存数据集并发布报表。"
                ),
                Map.of(
                    "fileId", "governance",
                    "fileName", "数据开发治理一体化运营系统需求说明.docx",
                    "chunkIndex", 0,
                    "content", "数据开发治理一体化运营系统需求及相关说明，请提供需求响应表。"
                )
            )
        ), "ok");
        Map<String, Object> metadata = Map.of(
            "evidenceEvaluation", Map.of(
                "contractVersion", "evidence_evaluation_contract_v1",
                "usefulRefs", List.of("doc://livedata-report#chunk=0"),
                "rejectedRefs", List.of("doc://governance#chunk=0")
            )
        );

        String observation = builder.buildSuccessObservation("document_search", output, "", metadata);

        assertThat(observation)
            .contains("Evidence evaluation selection (contractVersion=evidence_evaluation_contract_v1)")
            .contains("selectedEvidence=1")
            .contains("doc://livedata-report#chunk=0")
            .contains("基于livedata数据编织的报表开发")
            .doesNotContain("sourceRef: doc://governance#chunk=0")
            .doesNotContain("citation: doc://governance#chunk=0")
            .doesNotContain("数据开发治理一体化运营系统需求");
    }

    @Test
    void executionLockAcceptedRefsFilterDocumentEvidenceBeforeGraph() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(
                Map.of(
                    "fileId", "locked-doc",
                    "fileName", "locked.docx",
                    "chunkIndex", 0,
                    "content", "locked evidence should drive graph claims"
                ),
                Map.of(
                    "fileId", "retry-doc",
                    "fileName", "retry.docx",
                    "chunkIndex", 0,
                    "content", "retry evidence must be excluded after lock"
                )
            )
        ), "ok");
        Map<String, Object> metadata = Map.of(
            "executionLock", Map.of(
                "lockVersion", "evidence_execution_lock_v1",
                "status", "LOCKED",
                "lockedState", Map.of(
                    "accepted_refs", List.of("doc://locked-doc#chunk=0"),
                    "rejected_refs", List.of("doc://retry-doc#chunk=0"),
                    "evaluation", Map.of("relevance", 0.95, "answerability", 0.95, "usefulness", "HIGH")
                ),
                "executionConstraints", Map.of(
                    "immutable_steps", List.of(1),
                    "blocked_tools", List.of("document_search"),
                    "allow_only", List.of("final_answer")
                ),
                "lockGraph", Map.of(
                    "lockGraphVersion", "evidence_execution_lock_v2",
                    "locks", List.of(Map.of(
                        "lockId", "L1",
                        "weight", 0.9,
                        "type", "HARD",
                        "refs", List.of("doc://locked-doc#chunk=0"),
                        "sourceStepId", 1
                    )),
                    "conflicts", List.of(),
                    "propagation", Map.of(
                        "nodeWeights", Map.of("doc://locked-doc#chunk=0", 0.81),
                        "nodeLocks", Map.of("doc://locked-doc#chunk=0", List.of("L1")),
                        "claimWeights", Map.of("doc://locked-doc#chunk=0", 0.94)
                    ),
                    "dagFreeze", Map.of(
                        "status", "FULLY_FROZEN",
                        "freezeScore", 0.9,
                        "blockedTools", List.of("document_search"),
                        "allowedActions", List.of("claim_assembly", "final_answer")
                    )
                )
            )
        );

        String observation = builder.buildSuccessObservation("document_search", output, "", metadata);

        assertThat(observation)
            .contains("Evidence execution lock (lockVersion=evidence_execution_lock_v1)")
            .contains("Graph and claims must use locked accepted_refs only")
            .contains("lockGraphVersion=evidence_execution_lock_v2")
            .contains("dagFreeze=FULLY_FROZEN")
            .contains("propagatedNodes=1")
            .contains("nodeWeights={doc://locked-doc#chunk=0=0.81}")
            .contains("selectedEvidence=1")
            .contains("sourceRef: doc://locked-doc#chunk=0")
            .contains("score: 0.917")
            .contains("lockRef")
            .contains("supportWeight")
            .doesNotContain("sourceRef: doc://retry-doc#chunk=0")
            .doesNotContain("retry evidence must be excluded after lock");
    }

    @Test
    void canonicalStoreMarksSqlEvidence() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "ads.sql",
                "chunkIndex", 0,
                "content", "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i where data_date = 20260101"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
            .contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
            .contains("Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2)")
            .contains("decision: ANSWER_ALLOWED")
            .contains("contractHash:")
            .contains("graphViewHash:")
            .contains("---BEGIN_LOCKED_ANSWER---")
            .contains("---END_LOCKED_ANSWER---")
            .contains("answerContract: evidence_answer_contract_v2")
            .contains("Valid evidence paths:")
            .contains("type: SQL")
            .contains("type: TRUSTED_SQL")
            .contains("executionVerified: true")
            .contains("sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i")
            .contains("sourceRef: doc://file-1#chunk=0")
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void documentVisibilityConstraintFiltersUnselectedEvidenceBeforeObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "blocked unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("allowedDocuments=1")
            .contains("discardedEvidence=1")
            .contains("visible selected document evidence")
            .doesNotContain("blocked unselected document evidence");
    }

    @Test
    void superAdminBypassesDocumentVisibilityFilteringInObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "roles", List.of("ROLE_SUPER_ADMIN"),
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "super admin can inspect unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .doesNotContain("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("visible selected document evidence")
            .contains("super admin can inspect unselected document evidence");
    }
}
