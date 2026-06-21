package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentSearchControlPlaneTest {

    @Test
    void broadQueryStopsBeforeSearchAndDoesNotSpendBudget() {
        SearchService searchService = mock(SearchService.class);
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "analysis",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.total()).isZero();
        assertThat(result.retrievalState()).isNotNull();
        assertThat(result.retrievalState().lastAction()).isEqualTo(RetrievalControlAction.STOP);
        assertThat(result.retrievalState().budgetUsed().searchCalls()).isZero();
        assertThat(result.evidenceQuality()).isNotNull();
        assertThat(result.evidenceQuality().usable()).isFalse();
        assertThat(result.evidenceQuality().reason()).isEqualTo("empty_result");
        assertThat(result.retrievalEvents())
            .extracting(RetrievalEvent::step)
            .containsExactly(
                RetrievalControlStep.VALIDATOR,
                RetrievalControlStep.GATE,
                RetrievalControlStep.SCORER
            );
        verifyNoInteractions(searchService);
    }

    @Test
    void validQueryRecordsBudgetEventsAndEvidenceQuality() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME 2025 revenue policy"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME 2025 revenue policy",
            List.of("acme", "2025", "revenue", "policy"),
            List.of(searchResult()),
            1,
            8,
            1,
            8,
            1,
            false,
            7L,
            -1,
            null
        ));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2025 revenue policy",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.retrievalState().budgetUsed().searchCalls()).isEqualTo(1);
        assertThat(result.retrievalState().lastAction()).isEqualTo(RetrievalControlAction.SCORE);
        assertThat(result.evidenceQuality().usable()).isTrue();
        assertThat(result.evidenceQuality().reason()).isEqualTo("usable");
        assertThat(result.evidenceQuality().confidence()).isGreaterThan(0.0D);
        assertThat(result.retrievalEvents())
            .extracting(RetrievalEvent::step)
            .containsExactly(
                RetrievalControlStep.VALIDATOR,
                RetrievalControlStep.GATE,
                RetrievalControlStep.BUDGET,
                RetrievalControlStep.SEARCH,
                RetrievalControlStep.SCORER
            );
    }

    @Test
    void documentVisibilityConstraintReturnsOnlySelectedDocuments() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME 2025 revenue policy"),
            any(),
            any(),
            any(),
            eq("doc-1"),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME 2025 revenue policy",
            List.of("acme", "2025", "revenue", "policy"),
            List.of(searchResult(), blockedSearchResult()),
            2,
            8,
            1,
            8,
            1,
            false,
            7L,
            -1,
            null
        ));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2025 revenue policy",
            8,
            null,
            null,
            List.of("doc-1"),
            true,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.results())
            .extracting(DocumentEvidenceChunk::fileId)
            .containsExactly("doc-1");
        assertThat(result.results())
            .extracting(DocumentEvidenceChunk::content)
            .doesNotContain("Blocked private evidence should never be returned.");
        assertThat(result.retrievalEvents())
            .extracting(RetrievalEvent::reason)
            .anyMatch(reason -> reason.contains("document_visibility_enforced"));
    }

    @Test
    void superAdminBypassesDocumentVisibilityConstraint() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME 2025 revenue policy"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME 2025 revenue policy",
            List.of("acme", "2025", "revenue", "policy"),
            List.of(searchResult(), blockedSearchResult()),
            2,
            8,
            1,
            8,
            1,
            false,
            7L,
            -1,
            null
        ));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2025 revenue policy",
            8,
            null,
            null,
            List.of("doc-1"),
            true,
            null,
            null,
            "admin-user",
            List.of("ROLE_SUPER_ADMIN"),
            false
        ));

        assertThat(result.results())
            .extracting(DocumentEvidenceChunk::fileId)
            .containsExactlyInAnyOrder("doc-blocked", "doc-1");
        assertThat(result.retrievalEvents())
            .extracting(RetrievalEvent::reason)
            .anyMatch(reason -> reason.contains("document_visibility_bypassed reason=super_admin"));
    }

    @Test
    void titleOnlyHitReturnsDocumentOutlineWithoutEvidenceBody() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME 2026 asset reconciliation report"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME 2026 asset reconciliation report",
            List.of("acme", "2026", "asset", "reconciliation", "report"),
            List.of(titleOnlySearchResult()),
            1,
            8,
            1,
            8,
            1,
            false,
            4L,
            -1,
            null
        ));
        when(searchService.get(eq("doc-title-1"), any(SearchPermissionContext.class))).thenReturn(java.util.Optional.of(outlinedDocument()));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2026 asset reconciliation report",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.matchType()).isEqualTo(DocumentSearchMatchType.TITLE_ONLY_HIT);
        assertThat(result.retrievalSemantics().dataSafetyLevel()).isEqualTo(DocumentDataSafetyLevel.NO_EVIDENCE_BODY);
        assertThat(result.retrievalSemantics().canAnswerDirectly()).isFalse();
        assertThat(result.results()).isEmpty();
        assertThat(result.context()).isBlank();
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).tags()).containsExactly("finance");
        assertThat(result.outline()).isNotEmpty();
        assertThat(result.outlineSource()).isEqualTo(DocumentOutlineSource.DOC_STRUCTURE);
        assertThat(result.expansionPolicy().needsExpansion()).isTrue();
        assertThat(result.expansionPolicy().queryRequired()).isTrue();
        assertThat(result.expansionPolicy().originalQuery()).isEqualTo("ACME 2026 asset reconciliation report");
        assertThat(result.expansionPolicy().intent()).isNotBlank();
        assertThat(result.outline().get(0).sectionKeywords()).isNotEmpty();
        assertThat(result.outline().get(0).sectionEmbeddingRef()).startsWith("section://doc-title-1#");
    }

    @Test
    void titleOnlyContractDoesNotExposeRecallDebugSignals() throws Exception {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME asset report"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME asset report",
            List.of("acme", "asset", "report"),
            List.of(titleOnlySearchResult()),
            1,
            8,
            1,
            8,
            1,
            false,
            4L,
            -1,
            null
        ));
        when(searchService.get(eq("doc-title-1"), any(SearchPermissionContext.class))).thenReturn(java.util.Optional.of(outlinedDocument()));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME asset report",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json)
            .doesNotContain("matchedKeywords")
            .doesNotContain("fieldScores")
            .doesNotContain("scoreBreakdown")
            .doesNotContain("queryTokens")
            .doesNotContain("expandedTokens")
            .doesNotContain("significantTerms")
            .doesNotContain("memoryRecallRaw")
            .doesNotContain("memoryRecall");
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).tags()).containsExactly("finance");
    }

    @Test
    void strongTitleAnchorExpandsInsideDocumentInsteadOfTrustingFlatMatchedChunk() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.frontendQuickSearch(
            eq("ACME 2026 asset reconciliation report"),
            any(),
            any(),
            any(),
            any(),
            eq(1),
            eq(8),
            any(SearchPermissionContext.class)
        )).thenReturn(new SearchPage(
            "ACME 2026 asset reconciliation report",
            List.of("acme", "2026", "asset", "reconciliation", "report"),
            List.of(titleAnchoredSearchResult()),
            1,
            8,
            1,
            8,
            1,
            false,
            4L,
            -1,
            null
        ));
        when(searchService.get(eq("doc-title-1"), any(SearchPermissionContext.class))).thenReturn(java.util.Optional.of(outlinedDocument()));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2026 asset reconciliation report",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.matchType()).isEqualTo(DocumentSearchMatchType.MIXED_HIT);
        assertThat(result.documents()).hasSize(1);
        assertThat(result.outline()).isNotEmpty();
        assertThat(result.results()).isNotEmpty();
        assertThat(result.results())
            .extracting(DocumentEvidenceChunk::chunkType)
            .contains("expanded_evidence")
            .doesNotContain("table");
        assertThat(result.results().get(0).content()).contains("reconciliation");
    }

    @Test
    void expandReturnsEvidenceReadyChunksInsideOneDocument() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.get(eq("doc-title-1"), any(SearchPermissionContext.class))).thenReturn(java.util.Optional.of(outlinedDocument()));
        DocumentSearchEvidenceService service = newEvidenceService(searchService);

        DocumentSearchExpandResult result = service.expand(new DocumentSearchExpandRequest(
            "reconciliation rules",
            "doc-title-1",
            List.of("2. Reconciliation Rules"),
            4,
            2,
            4,
            4000,
            null,
            null,
            null,
            false
        ));

        assertThat(result.evidenceChunks()).isNotEmpty();
        assertThat(result.evidenceChunks().get(0).isEvidenceReady()).isTrue();
        assertThat(result.evidenceChunks().get(0).evidenceGrade()).isIn(DocumentEvidenceGrade.A, DocumentEvidenceGrade.B);
        assertThat(result.evidenceChunks().get(0).text()).contains("reconciliation");
        assertThat(result.retrievalSemantics().dataSafetyLevel()).isEqualTo(DocumentDataSafetyLevel.EVIDENCE_BODY);
        assertThat(result.expansionPolicy().originalQuery()).isEqualTo("reconciliation rules");
        assertThat(result.expansionPolicy().intent()).isNotBlank();
        assertThat(result.reasoning()).isNotNull();
        assertThat(result.reasoning().graph().nodes()).isNotEmpty();
        assertThat(result.reasoning().reasoningChain()).isNotEmpty();
        assertThat(result.sectionGraph().nodes()).isNotEmpty();
        assertThat(result.knowledgeGraph().nodes())
            .extracting(KnowledgeNode::type)
            .contains(KnowledgeNodeType.SECTION, KnowledgeNodeType.EVIDENCE);
        assertThat(result.knowledgeGraph().edges())
            .extracting(KnowledgeEdge::type)
            .contains(KnowledgeRelationType.SECTION_EVIDENCE, KnowledgeRelationType.EVIDENCE_EVIDENCE);
        assertThat(result.traversal().nodeScores())
            .extracting(KnowledgeNodeScore::nodeType)
            .contains(KnowledgeNodeType.SECTION, KnowledgeNodeType.EVIDENCE);
        assertThat(result.traversal().paths()).isNotEmpty();
        assertThat(result.knowledgeReasoning().clusters()).isNotEmpty();
        assertThat(result.knowledgeReasoning().steps())
            .extracting(KnowledgeReasoningStep::step)
            .contains("scope", "support", "conclusion");
        assertThat(result.knowledgeReasoning().conclusionMode()).isIn("FULL", "PARTIAL");
    }

    @Test
    void expandSelectsSectionsBeforeChunks() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.get(eq("doc-structured"), any(SearchPermissionContext.class)))
            .thenReturn(java.util.Optional.of(structuredDocument()));
        DocumentSearchEvidenceService service = newEvidenceService(searchService, properties -> {
            properties.setChunkSize(220);
            properties.setChunkOverlap(0);
        });

        DocumentSearchExpandResult result = service.expand(new DocumentSearchExpandRequest(
            "threshold breach reconciliation rules",
            "doc-structured",
            List.of(),
            4,
            1,
            3,
            6000,
            null,
            null,
            null,
            false
        ));

        assertThat(result.evidenceChunks()).isNotEmpty();
        assertThat(result.evidenceChunks())
            .extracting(DocumentExpandedEvidenceChunk::section)
            .allMatch(section -> section.contains("Reconciliation Rules"));
        assertThat(result.sectionGraph().nodes()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.sectionGraph().edges())
            .extracting(SectionEdge::type)
            .contains(SectionEdgeType.CONTINUES);
        assertThat(result.knowledgeGraph().nodes())
            .extracting(KnowledgeNode::type)
            .contains(KnowledgeNodeType.SECTION, KnowledgeNodeType.EVIDENCE);
        assertThat(result.knowledgeGraph().edges())
            .extracting(KnowledgeEdge::type)
            .contains(KnowledgeRelationType.SECTION_SECTION, KnowledgeRelationType.SECTION_EVIDENCE);
        assertThat(result.traversal().selectedSections()).isGreaterThanOrEqualTo(1);
        assertThat(result.traversal().selectedEvidence()).isGreaterThanOrEqualTo(1);
        assertThat(result.traversal().paths())
            .allSatisfy(path -> assertThat(path.nodeIds()).hasSizeGreaterThanOrEqualTo(2));
        assertThat(result.knowledgeReasoning().clusters())
            .allSatisfy(cluster -> assertThat(cluster.evidenceNodeIds()).isNotEmpty());
        assertThat(result.knowledgeReasoning().confidence()).isGreaterThan(0.0D);
    }

    private DocumentSearchEvidenceService newEvidenceService(SearchService searchService) {
        return newEvidenceService(searchService, properties -> {
        });
    }

    @Test
    void budgetExhaustionStopsBeforeSearch() {
        SearchService searchService = mock(SearchService.class);
        DocumentSearchEvidenceService service = newEvidenceService(
            searchService,
            properties -> properties.getRetrievalControl().setMaxSearchCalls(0)
        );

        DocumentSearchResult result = service.search(new DocumentSearchRequest(
            "ACME 2025 revenue policy",
            8,
            null,
            null,
            null,
            null,
            null,
            false
        ));

        assertThat(result.total()).isZero();
        assertThat(result.retrievalState().lastAction()).isEqualTo(RetrievalControlAction.STOP);
        assertThat(result.retrievalState().budgetUsed().searchCalls()).isZero();
        assertThat(result.retrievalEvents())
            .extracting(RetrievalEvent::step)
            .containsExactly(
                RetrievalControlStep.VALIDATOR,
                RetrievalControlStep.GATE,
                RetrievalControlStep.BUDGET,
                RetrievalControlStep.SCORER
            );
        assertThat(result.retrievalEvents().get(2).reason()).isEqualTo("budget_exhausted");
        verifyNoInteractions(searchService);
    }

    private DocumentSearchEvidenceService newEvidenceService(SearchService searchService,
                                                             Consumer<SearchProperties> propertiesCustomizer) {
        SearchProperties properties = new SearchProperties();
        propertiesCustomizer.accept(properties);
        SearchTokenizer tokenizer = new SearchTokenizer();
        RetrievalRuleService ruleService = mock(RetrievalRuleService.class);
        when(ruleService.snapshot()).thenReturn(new RetrievalRuleService.RuleSnapshot(List.of(), List.of(), List.of(), List.of(), 0L));
        QueryIntentClassifier intentClassifier = new QueryIntentClassifier(tokenizer, ruleService);
        return new DocumentSearchEvidenceService(
            searchService,
            tokenizer,
            intentClassifier,
            new EvidenceContextFormatter(),
            properties,
            new RetrievalQueryValidator(tokenizer, properties),
            new RetrievalEvidenceQualityScorer(properties),
            new TextChunker()
        );
    }

    private SearchResult searchResult() {
        return new SearchResult(
            "doc-1",
            "ACME 2025 Revenue Policy",
            "ACME 2025 revenue policy summary",
            "upload",
            "2025-01-01",
            "acme-policy.pdf",
            "pdf",
            null,
            List.of("finance"),
            List.of("ACME"),
            List.of("software"),
            90,
            null,
            List.of("ACME", "2025", "revenue", "policy"),
            List.of(new SearchMatchedChunk(
                "doc-1",
                "acme-policy.pdf",
                "Revenue",
                "policy",
                "chunk-1",
                1,
                0.1F,
                "ACME 2025 revenue policy requires quarterly review.",
                "ACME 2025 revenue policy requires quarterly review.",
                8.8F,
                SearchPermissionContext.ANONYMOUS_USER,
                SearchPermissionContext.ANONYMOUS_USER,
                "public",
                List.of()
            )),
            "vg-1",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
    }

    private SearchResult blockedSearchResult() {
        return new SearchResult(
            "doc-blocked",
            "ACME Blocked Document",
            "Blocked private evidence should not be visible",
            "upload",
            "2025-01-02",
            "blocked.pdf",
            "pdf",
            null,
            List.of("finance"),
            List.of("ACME"),
            List.of("software"),
            99,
            null,
            List.of("ACME", "blocked"),
            List.of(new SearchMatchedChunk(
                "doc-blocked",
                "blocked.pdf",
                "Private",
                "policy",
                "blocked-chunk",
                1,
                0.1F,
                "Blocked private evidence should never be returned.",
                "Blocked private evidence should never be returned.",
                9.9F,
                SearchPermissionContext.ANONYMOUS_USER,
                SearchPermissionContext.ANONYMOUS_USER,
                "public",
                List.of()
            )),
            "vg-blocked",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
    }

    private SearchResult titleOnlySearchResult() {
        return new SearchResult(
            "doc-title-1",
            "ACME 2026 Asset Reconciliation Report",
            "Document outline for planning only.",
            "upload",
            "2026-01-01",
            "ACME-2026-asset-reconciliation-report.pdf",
            "pdf",
            null,
            List.of("finance"),
            List.of("ACME"),
            List.of("software"),
            92,
            new SearchScoreBreakdown(
                0,
                48,
                0,
                0,
                0,
                0,
                0,
                0,
                24,
                0,
                0.0D,
                Map.of("title", 48, "fileName", 40, "content", 0)
            ),
            List.of("ACME", "2026", "asset", "reconciliation", "report"),
            List.of(),
            "vg-title-1",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
    }

    private SearchResult titleAnchoredSearchResult() {
        return new SearchResult(
            "doc-title-1",
            "ACME 2026 Asset Reconciliation Report",
            "Document with a strong title match and a noisy flat chunk.",
            "upload",
            "2026-01-01",
            "ACME-2026-asset-reconciliation-report.pdf",
            "pdf",
            null,
            List.of("finance"),
            List.of("ACME"),
            List.of("software"),
            95,
            new SearchScoreBreakdown(
                8,
                48,
                0,
                0,
                0,
                0,
                0,
                0,
                24,
                0,
                0.0D,
                Map.of("title", 48, "fileName", 40, "content", 8)
            ),
            List.of("ACME", "2026", "asset", "reconciliation", "report"),
            List.of(new SearchMatchedChunk(
                "doc-title-1",
                "ACME-2026-asset-reconciliation-report.pdf",
                "Appendix",
                "table",
                "noisy-chunk",
                9,
                0.9F,
                "Noisy appendix table that should not drive document-level answer assembly.",
                "Noisy appendix table that should not drive document-level answer assembly.",
                9.5F,
                SearchPermissionContext.DEFAULT_TENANT,
                SearchPermissionContext.ANONYMOUS_USER,
                "public",
                List.of()
            )),
            "vg-title-1",
            1,
            true,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of(),
            "active",
            System.currentTimeMillis(),
            null,
            null
        );
    }

    private SearchDocument outlinedDocument() {
        return SearchDocument.builder()
            .docId("doc-title-1")
            .title("ACME 2026 Asset Reconciliation Report")
            .fileName("ACME-2026-asset-reconciliation-report.pdf")
            .documentType("pdf")
            .content("""
                1. Data Sources
                The report uses custody positions and valuation feeds for daily asset checks.

                2. Reconciliation Rules
                The reconciliation rules compare model assets with accounting assets and flag threshold breaches.

                3. Exception Handling
                Exceptions are routed to operations with owner, reason, and expected close date.
                """)
            .tenantId(SearchPermissionContext.DEFAULT_TENANT)
            .userId(SearchPermissionContext.ANONYMOUS_USER)
            .visibility("public")
            .permissionRoles(List.of())
            .build();
    }

    private SearchDocument structuredDocument() {
        return SearchDocument.builder()
            .docId("doc-structured")
            .title("ACME Structured Operations Manual")
            .fileName("ACME-structured-operations-manual.pdf")
            .documentType("pdf")
            .content("""
                1. Data Sources
                Custody feeds provide positions, valuation dates, account identifiers, upstream batch status,
                source timestamps, and portfolio lineage. This section intentionally contains operational
                background and does not define reconciliation threshold breach handling.

                2. Reconciliation Rules
                The reconciliation rules compare model assets with accounting assets. Threshold breach
                evidence is generated when the variance exceeds configured tolerance. Each breach records
                owner, reason, severity, affected portfolio, expected close date, and review status.
                Reconciliation rules also define how repeated threshold breach cases are grouped.

                3. Exception Handling
                Exception handling routes rejected jobs to operations. Owners review failed files, retry
                the job when source data arrives, and close the item after operational approval.
                """)
            .tenantId(SearchPermissionContext.DEFAULT_TENANT)
            .userId(SearchPermissionContext.ANONYMOUS_USER)
            .visibility("public")
            .permissionRoles(List.of())
            .build();
    }
}
