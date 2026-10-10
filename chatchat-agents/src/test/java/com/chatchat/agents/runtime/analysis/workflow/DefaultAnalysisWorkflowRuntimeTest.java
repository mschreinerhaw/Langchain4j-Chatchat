package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.ComputationEvidence;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.AgentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;
import com.chatchat.common.runtime.analysis.recovery.EvidenceRecoveryResult;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStatus;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStrategy;
import com.chatchat.common.runtime.analysis.recovery.RecoveryLevel;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.execution.LocalWorkflowRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAnalysisWorkflowRuntimeTest {
    @Test void archivesModelAssessmentWithOriginalEvidenceAndLimitations() {
        var validation = Map.<String,Object>of("evidenceStatus", "PARTIAL", "missingEvidence", List.of("source#chunk2"),
            "requiresReanalysis", false, "conclusionScope", "SNAPSHOT_ONLY");
        var item = new ComputationEvidence("e1", "sum", List.of("source"), "42", Map.of());
        AnalysisWorkflow workflow = new AnalysisWorkflow() {
            public AnalysisWorkflowType type() { return AnalysisWorkflowType.COMPUTATION; }
            public String workflowId() { return "test.claim-audit"; }
            public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            public AnalysisExecutionOutcome execute(AnalysisContext context) {
                return new AnalysisExecutionOutcome(null, type(), null, new VerificationResult(true, List.of(item), List.of()),
                    new EvidenceBundle(null, List.of(item), List.of("missing chunk"), Map.of("provenance", "original")),
                    "Bounded analysis", Map.of("modelEvidenceAssessmentAudit", validation,
                        "modelEvidenceAssessmentHistory", List.of(validation)));
            }
        };
        var archive = org.mockito.Mockito.mock(AnalysisEvidenceArchivePort.class);
        org.mockito.Mockito.when(archive.archive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> {
                EvidenceBundle bundle = call.getArgument(1);
                assertThat(bundle.metadata()).containsEntry("provenance", "original").containsEntry("modelEvidenceAssessmentAudit", validation)
                    .containsEntry("modelEvidenceAssessmentHistory", List.of(validation));
                assertThat(bundle.evidence()).containsExactly(item);
                assertThat(bundle.limitations()).containsExactly("missing chunk");
                return new AnalysisEvidenceArchivePort.Reference("archive", "hash", 42);
            });
        var context = new AnalysisContext("calculate", new KernelDataScope("tenant", "user", "request", null, "run", null, Map.of()),
            "skill", List.of(), List.of(), List.of(), new AnalysisIntent("CALCULATION", List.of(),
                Set.of(AnalysisCapability.COMPUTATION), "UNSPECIFIED", true), Map.of());
        var result = new DefaultAnalysisWorkflowRuntime(List.of(workflow), null, archive).analyze(context);
        assertThat(result.evidenceBundle().metadata()).containsEntry("modelEvidenceAssessmentAudit", validation);
        org.mockito.Mockito.verify(archive).archive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
    @Test
    void missingAssetMetadataNeverTriggersDataOrDocumentRecovery() {
        AnalysisWorkflow guidance = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.ASSET_GUIDANCE; }
            @Override public String workflowId() { return "test.asset-guidance"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(false, List.of(), List.of("No template")),
                    EvidenceBundle.empty("No template"), "No template", Map.of());
            }
        };
        EvidenceRecoveryWorkflow recovery = org.mockito.Mockito.mock(EvidenceRecoveryWorkflow.class);
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(guidance), null, null, List.of(recovery));
        var context = new AnalysisContext("这个 API 怎么用", KernelDataScope.system("asset-test"), "agent", List.of(),
            List.of(), List.of(), new AnalysisIntent("ASSET_GUIDANCE", List.of(),
                Set.of(AnalysisCapability.ASSET_GUIDANCE), "UNSPECIFIED", true), Map.of());
        assertThat(runtime.analyze(context).metadata()).containsEntry("workflowFamily", "ASSET_GUIDANCE")
            .containsEntry("runtimePublicStatus", "NO_PRESENTABLE_RESULT")
            .containsEntry("runtimeGuidanceDecision", "INSUFFICIENT_EVIDENCE");
        org.mockito.Mockito.verifyNoInteractions(recovery);
    }

    @Test
    void runtimeNeverReentersGuidanceOnMissingEvidence() {
        var calls = new java.util.ArrayList<Integer>();
        AnalysisWorkflow guidance = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.ASSET_GUIDANCE; }
            @Override public String workflowId() { return "test.guidance-paging"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                int offset = ((Number) context.attributes().getOrDefault("assetGuidanceToolOffset", 0)).intValue();
                calls.add(offset);
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(false, List.of(), List.of()), EvidenceBundle.empty("No metadata"),
                    "Explanation is not success", Map.of("guidanceState", "GUIDANCE_CONTEXT_REQUIRED", "guidanceNextToolOffset", offset + 4));
            }
        };
        EvidenceRecoveryWorkflow recovery = org.mockito.Mockito.mock(EvidenceRecoveryWorkflow.class);
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(guidance), null, null, List.of(recovery));
        var context = new AnalysisContext("这个 API 怎么用", KernelDataScope.system("asset-pages"), "agent", List.of(),
            List.of(), List.of(), new AnalysisIntent("ASSET_GUIDANCE", List.of(), Set.of(AnalysisCapability.ASSET_GUIDANCE),
                "UNSPECIFIED", true), Map.of("evidenceRecoveryMaxRounds", 1));
        var result = runtime.analyze(context);
        assertThat(calls).containsExactly(0);
        assertThat(result.metadata()).containsEntry("runtimePublicStatus", "NO_PRESENTABLE_RESULT")
            .containsEntry("guidanceExecutionPolicy", "PLAN_ONCE_ACQUIRE_ONCE_NO_REENTRY");
        org.mockito.Mockito.verifyNoInteractions(recovery);
    }

    @Test
    void runtimeAcceptsReadyGuidanceWithoutReentry() {
        var calls = new java.util.ArrayList<Integer>();
        AnalysisEvidence evidence = org.mockito.Mockito.mock(AnalysisEvidence.class);
        AnalysisWorkflow guidance = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.ASSET_GUIDANCE; }
            @Override public String workflowId() { return "test.guidance-ready"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                int offset = ((Number) context.attributes().getOrDefault("assetGuidanceToolOffset", 0)).intValue();
                calls.add(offset);
                List<AnalysisEvidence> found = List.of(evidence);
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(!found.isEmpty(), found, List.of()),
                    new EvidenceBundle(null, found, List.of(), Map.of()), "guidance",
                    Map.of("guidanceState", "GUIDANCE_READY",
                        "guidanceNextToolOffset", offset + 4));
            }
        };
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(guidance));
        var context = new AnalysisContext("API 怎么用", KernelDataScope.system("ready"), "agent", List.of(),
            List.of(), List.of(), new AnalysisIntent("ASSET_GUIDANCE", List.of(), Set.of(AnalysisCapability.ASSET_GUIDANCE),
                "UNSPECIFIED", true), Map.of());
        var result = runtime.analyze(context);
        assertThat(calls).containsExactly(0);
        assertThat(result.metadata()).containsEntry("runtimeGuidanceDecision", "GUIDANCE_READY")
            .containsEntry("runtimePublicStatus", "PARTIAL_SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesStructuralEvidenceGapThroughDeterministicRecoveryBeforeSynthesis() {
        DocumentAnalysisEvidence partial = new DocumentAnalysisEvidence(
            "e-1", "doc-1", "chunk-6", "install.md", "3.1.1.6", "install.md#3.1.1.6",
            "partial installation step", 0.9D, Map.of("truncated", true, "chunkIndex", 6));
        AnalysisWorkflow document = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOCUMENT; }
            @Override public String workflowId() { return "test.document"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(true, List.of(partial), List.of()),
                    new EvidenceBundle(null, List.of(partial), List.of(), Map.of("sourceTruncated", true)),
                    "partial", Map.of());
            }
        };
        java.util.concurrent.atomic.AtomicReference<EvidenceGap> routedGap = new java.util.concurrent.atomic.AtomicReference<>();
        EvidenceRecoveryWorkflow recovery = new EvidenceRecoveryWorkflow() {
            @Override public boolean supports(AnalysisContext context, EvidenceGap gap) { return true; }
            @Override public int priority() { return 100; }
            @Override public EvidenceRecoveryResult recover(AnalysisContext context, EvidenceBundle current,
                                                             EvidenceGap gap, int round) {
                routedGap.set(gap);
                DocumentAnalysisEvidence next = new DocumentAnalysisEvidence(
                    "e-2", "doc-1", "chunk-7", "install.md", "3.1.1.7", "install.md#3.1.1.7",
                    "next installation step", 0.92D, Map.of("chunkIndex", 7, "recovered", true));
                return new EvidenceRecoveryResult(RecoveryStatus.RETRY_REQUIRED,
                    new EvidenceBundle(null, List.of(partial, next), List.of(), Map.of(
                        "recoveryComplete", true, "sequenceComplete", true,
                        "sourceTruncated", false, "evidenceCoverage", 1.0D)),
                    List.of(), round, RecoveryStrategy.ADJACENT_SECTION_SEARCH,
                    RecoveryLevel.L3_ADJACENT_SECTION,
                    Map.of("recoveryComplete", true, "evidenceCoverage", 1.0D));
            }
        };
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(
            List.of(document), null, null, List.of(recovery));
        AnalysisIntent intent = new AnalysisIntent("INSTALL", List.of(),
            Set.of(AnalysisCapability.DOCUMENT_SEARCH), "UNSPECIFIED", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext(
            "获取安装步骤", KernelDataScope.system("request-recovery"), "skill",
            List.of("doc-1"), List.of(), List.of(), intent, Map.of()));

        assertThat(routedGap.get().reason()).isEqualTo(EvidenceGapReason.SOURCE_TRUNCATED);
        assertThat(result.evidenceBundle().evidence()).hasSize(2);
        assertThat(result.metadata())
            .containsEntry("runtimePrimaryPath", "DOCUMENT_RETRIEVAL")
            .containsEntry("runtimeRoute", "CONTINUE_ANALYSIS")
            .containsEntry("evidenceRecoveryStatus", "NO_NEW_EVIDENCE");
        List<Map<String, Object>> trace = (List<Map<String, Object>>) result.metadata().get("evidenceRecoveryTrace");
        assertThat(trace).hasSize(2).first().satisfies(entry -> assertThat(entry)
            .containsEntry("gap", "SOURCE_TRUNCATED")
            .containsEntry("status", "RETRY_REQUIRED"));
    }

    @Test
    void continuesWithEmptyEvidenceWhenNoRecoveryWorkflowExists() {
        AnalysisWorkflow document = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOCUMENT; }
            @Override public String workflowId() { return "test.empty-document"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(false, List.of(), List.of("empty")),
                    EvidenceBundle.empty("empty"), "", Map.of());
            }
        };
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(List.of(document));
        AnalysisIntent intent = new AnalysisIntent("LOOKUP", List.of(),
            Set.of(AnalysisCapability.DOCUMENT_SEARCH), "UNSPECIFIED", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext(
            "lookup document", KernelDataScope.system("request-empty"), "skill",
            List.of("doc-1"), List.of(), List.of(), intent, Map.of()));

        assertThat(result.metadata())
            .containsEntry("runtimeRoute", "CONTINUE_ANALYSIS")
            .containsEntry("evidenceRecoveryStatus", "NO_WORKFLOW");
        assertThat(result.verification().accepted()).isFalse();
    }

    @Test
    void archiveFailurePreservesAnalysisAndReportsTransportFailure() {
        AnalysisCapabilityOperator operator = new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(new ComputationEvidence(
                    "e-1", "sum", List.of("input"), "42", Map.of())), Map.of(), List.of());
            }
        };
        AnalysisEvidenceArchivePort brokenArchive = new AnalysisEvidenceArchivePort() {
            @Override public Reference archive(AnalysisContext context, EvidenceBundle bundle) {
                throw new IllegalStateException("database unavailable");
            }
            @Override public java.util.Optional<ArchivedEvidence> read(String tenant, String user, String id) {
                return java.util.Optional.empty();
            }
        };
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(operator)))), null, brokenArchive);
        var context = new AnalysisContext("sum input", new KernelDataScope("tenant", "user", "request",
            null, "run", null, Map.of()), "skill", List.of(), List.of(), List.of(),
            new AnalysisIntent("CALCULATION", List.of(), Set.of(AnalysisCapability.COMPUTATION),
                "UNSPECIFIED", true), Map.of());

        var result = runtime.analyze(context);

        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).hasSize(1);
        assertThat(result.metadata()).containsEntry("evidenceArchiveStatus", "FAILED");
    }

    @Test
    void operatorRejectsEvidenceForAnotherCapability() {
        AnalysisCapabilityOperator wrong = new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(new ToolAnalysisEvidence(
                    "tool-1", "calculator", "call-1", "42", Map.of())), Map.of(), List.of());
            }
        };
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(wrong)))));
        var intent = new AnalysisIntent("CALCULATION", List.of(), Set.of(AnalysisCapability.COMPUTATION),
            "UNSPECIFIED", true);

        var result = runtime.analyze(new AnalysisContext("calculate", KernelDataScope.system("request-1"),
            "skill", List.of(), List.of(), List.of(), intent, Map.of()));

        assertThat(result.verification().accepted()).isFalse();
        assertThat(result.evidenceBundle().evidence()).isEmpty();
    }

    @Test
    void compositeNeverPassesRejectedChildEvidenceToDomainAgent() {
        java.util.concurrent.atomic.AtomicInteger domainCalls = new java.util.concurrent.atomic.AtomicInteger();
        StructuredDataEvidence rejectedEvidence = new StructuredDataEvidence("data-rejected", "private",
            "select *", 1, "2026-09-25", "unverified", Map.of());
        AnalysisWorkflow rejectedData = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.STRUCTURED_DATA; }
            @Override public String workflowId() { return "test.rejected-data"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(false, List.of(), List.of("data rejected")),
                    new EvidenceBundle(null, List.of(rejectedEvidence), List.of(), Map.of()), "", Map.of());
            }
        };
        AnalysisWorkflow domain = new AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.FEDERATED_AGENT; }
            @Override public String workflowId() { return "test.domain"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                domainCalls.incrementAndGet();
                var claim = new AgentAnalysisEvidence("agent-1", "agent", "exec", List.of(), "analysis", Map.of());
                return new AnalysisExecutionOutcome(null, type(), null,
                    new VerificationResult(true, List.of(claim), List.of()),
                    new EvidenceBundle(null, List.of(claim), List.of(), Map.of()), "analysis", Map.of());
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("data", rejectedData);
        beans.addBean("domain", domain);
        CompositeAnalysisWorkflow composite = new CompositeAnalysisWorkflow(beans.getBeanProvider(AnalysisWorkflow.class));
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(rejectedData, domain, composite));
        AnalysisIntent intent = new AnalysisIntent("test", List.of(),
            Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE, AnalysisCapability.STRUCTURED_DATA),
            "UNSPECIFIED", true);
        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext("analyze", KernelDataScope.system("r"),
            "skill", List.of(), List.of(), List.of(), intent, Map.of()));

        assertThat(result.verification().accepted()).isFalse();
        assertThat(result.evidenceBundle().evidence()).isEmpty();
        assertThat(result.synthesis()).isBlank();
        assertThat(domainCalls.get()).isZero();
    }
    @Test
    void routesComputationIntentThroughParentLifecycleAndOperator() {
        AnalysisCapabilityOperator operator = new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(new ComputationEvidence(
                    "e-1", "max_drawdown", List.of("prices"), "-0.134", Map.of())), Map.of(), List.of());
            }
        };
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(operator)));
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(List.of(computation));
        KernelDataScope scope = KernelDataScope.system("request-1");
        AnalysisIntent intent = new AnalysisIntent("RISK_CALCULATION", List.of(),
            Set.of(AnalysisCapability.COMPUTATION), "UNSPECIFIED", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext("calculate max drawdown", scope,
            "risk-skill", List.of(), List.of(), List.of(), intent, Map.of()));

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPUTATION);
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).singleElement()
            .isInstanceOf(ComputationEvidence.class);
        assertThat(result.metadata()).containsEntry("executionMode", "INLINE");
    }

    @Test
    void compositeWorkflowMergesVerifiedEvidenceFromEachCapability() {
        AnalysisOperatorRegistry operators = new AnalysisOperatorRegistry(List.of(
            operator(AnalysisCapability.COMPUTATION, new ComputationEvidence(
                "calculation-1", "sum", List.of("data-1"), "42", Map.of())),
            operator(AnalysisCapability.STRUCTURED_DATA, new StructuredDataEvidence(
                "data-1", "orders", "select sum(amount) from orders", 1L,
                "2026-09-23", "42", Map.of()))));
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(operators);
        StructuredDataAnalysisWorkflow structured = new StructuredDataAnalysisWorkflow(operators);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("computation", computation);
        beans.addBean("structured", structured);
        CompositeAnalysisWorkflow composite = new CompositeAnalysisWorkflow(
            beans.getBeanProvider(AnalysisWorkflow.class));
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(
            List.of(computation, structured, composite));
        AnalysisIntent intent = new AnalysisIntent("ORDER_TOTAL", List.of(),
            Set.of(AnalysisCapability.STRUCTURED_DATA, AnalysisCapability.COMPUTATION),
            "CURRENT", true);

        AnalysisExecutionOutcome result = runtime.analyze(new AnalysisContext("calculate current order total",
            KernelDataScope.system("request-2"), "finance-skill", List.of(), List.of(), List.of(),
            intent, Map.of()));

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPOSITE);
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence())
            .extracting(AnalysisEvidence::capability)
            .containsExactlyInAnyOrder(AnalysisCapability.STRUCTURED_DATA, AnalysisCapability.COMPUTATION);
    }

    @Test
    void explicitlyDurableAnalysisUsesWorkflowRuntimeWithoutChangingChildWorkflow() {
        AnalysisCapabilityOperator operator = operator(AnalysisCapability.COMPUTATION,
            new ComputationEvidence("e-2", "sum", List.of("input"), "7", Map.of()));
        ComputationAnalysisWorkflow computation = new ComputationAnalysisWorkflow(
            new AnalysisOperatorRegistry(List.of(operator)));
        LocalWorkflowRuntime workflowRuntime = new LocalWorkflowRuntime(
            ForkJoinPool.commonPool(), new AgentRuntimeProperties());
        DefaultAnalysisWorkflowRuntime runtime = new DefaultAnalysisWorkflowRuntime(
            List.of(computation), workflowRuntime);
        KernelDataScope scope = KernelDataScope.system("request-durable-1");
        AnalysisContext context = new AnalysisContext("calculate total", scope, "math-skill",
            List.of(), List.of(), List.of(),
            new AnalysisIntent("TOTAL", List.of(), Set.of(AnalysisCapability.COMPUTATION),
                "UNSPECIFIED", true), Map.of(AnalysisContext.EXECUTION_MODE_ATTRIBUTE, "DURABLE"));

        AnalysisExecutionOutcome result = runtime.analyze(context);

        assertThat(result.workflowType()).isEqualTo(AnalysisWorkflowType.COMPUTATION);
        assertThat(result.metadata()).containsEntry("executionMode", "DURABLE");
        assertThat(result.metadata().get("runtimeWorkflowId")).asString().startsWith("analysis-");
        assertThat(workflowRuntime.activeExecutionCount()).isZero();
    }

    private AnalysisCapabilityOperator operator(AnalysisCapability capability, AnalysisEvidence evidence) {
        return new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return capability; }
            @Override public boolean available(AnalysisContext context) { return true; }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                return new WorkflowExecutionResult(List.of(evidence), Map.of(), List.of());
            }
        };
    }
}
