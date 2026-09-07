package com.chatchat.agents.orchestration.answer;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerEvidenceAuditServiceTest {
    @Test
    void modelAuthoredAnalysisAuditRecordsFailureWithoutChangingTheReportBody() {
        var service = new AnswerEvidenceAuditService(
            new AnswerEvidenceLedgerCompiler(), new AnswerUserFacingPolicy(null));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analyticalReport", Map.of("publicationMode", "MODEL_REPORT_MARKDOWN"));
        String body = "# Model-authored report\n\nThe observed value is 42 [evidence: tool://missing#result=1].";

        String result = service.attachLedger(body, metadata, List.of(), List.of());

        assertThat(result).isEqualTo(body).doesNotContain("证据完整性提示");
        assertThat(metadata).containsEntry("claimCoverageStatus", "FAIL")
            .containsEntry("answerClaimAuditPassed", false)
            .containsEntry("evidenceWarningSuppressedForModelAuthoredReport", true)
            .containsEntry("answerEvidenceUserVisible", false);
    }

    @Test
    void repeatedAuditKeepsOneNoticeAndRetainsFailureStatus() {
        var service = new AnswerEvidenceAuditService(
            new AnswerEvidenceLedgerCompiler(), new AnswerUserFacingPolicy(null));
        Map<String, Object> metadata = new LinkedHashMap<>();
        String body = "账户总资产为847174.25元 [evidence: tool://missing#result=1]。";

        String first = service.attachLedger(body, metadata, List.of(), List.of());
        String second = service.attachLedger(first, metadata, List.of(), List.of());

        assertThat(first).containsOnlyOnce("证据完整性提示");
        assertThat(second).isEqualTo(first);
        assertThat(metadata).containsEntry("claimCoverageStatus", "FAIL")
            .containsEntry("answerClaimAuditPassed", false);
    }
}
