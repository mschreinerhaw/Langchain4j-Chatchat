package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveAnalysisControllerTest {
    private final AdaptiveAnalysisController controller = new AdaptiveAnalysisController();
    private final EvidenceGap gap = new EvidenceGap(EvidenceGapReason.CLAIM_UNSUPPORTED,
        "claim", null, null, 0, 1, false, false, List.of("source needed"));

    @Test void verificationAndGapResolutionAreBothRequiredForDelivery() {
        assertThat(controller.decide(new AdaptiveAnalysisController.Feedback(List.of(), false, true, false, false), 0, 2).reason())
            .isEqualTo("VERIFICATION_REQUIRED");
        assertThat(controller.decide(new AdaptiveAnalysisController.Feedback(List.of(gap), true, true, false, false), 0, 2).action())
            .isEqualTo(AdaptiveAnalysisController.Action.RECOVER);
        assertThat(controller.decide(new AdaptiveAnalysisController.Feedback(List.of(), true, false, false, false), 2, 2).action())
            .isEqualTo(AdaptiveAnalysisController.Action.DELIVER);
    }

    @Test void providerFailureCannotBeReportedAsVerifiedDelivery() {
        assertThat(controller.decide(new AdaptiveAnalysisController.Feedback(List.of(), true, true, true, false), 1, 2))
            .isEqualTo(new AdaptiveAnalysisController.Decision(AdaptiveAnalysisController.Action.STOP, "FAILED"));
    }

    @Test void runtimeCeilingCannotBeRaisedByConfiguration() {
        assertThat(controller.decide(new AdaptiveAnalysisController.Feedback(List.of(gap), false, true, false, false), 2, 999).reason())
            .isEqualTo("BUDGET_EXHAUSTED");
        assertThat(controller.decideReplan(new AdaptiveAnalysisController.ReplanFeedback(false, 2, 999, true, false, true, true)).reason())
            .isEqualTo("runtime_attempt_limit");
    }

    @Test void authorizationGatePrecedesAllRecoveryPaths() {
        assertThat(controller.decideReplan(new AdaptiveAnalysisController.ReplanFeedback(true, 0, 3, true, true, true, true)))
            .isEqualTo(new AdaptiveAnalysisController.ReplanDecision(false, false, "authorization_required"));
    }

    @org.junit.jupiter.api.Test void modelContinuationAndReplanDependOnlyOnAdmissionAndBudget() {
        var controller = new AdaptiveAnalysisController();
        var metadata = java.util.Map.<String,Object>of("modelAnalysisProtocol", "model_native_analysis.v2", "modelDecision", java.util.Map.of("action", "CONTINUE"));
        org.assertj.core.api.Assertions.assertThat(controller.decideModel(metadata,0,2).action()).isEqualTo(AdaptiveAnalysisController.Action.RECOVER);
        org.assertj.core.api.Assertions.assertThat(controller.admitModelReplan(false,0,3).allowed()).isTrue();
        org.assertj.core.api.Assertions.assertThat(controller.admitModelReplan(true,0,3).allowed()).isFalse();
        org.assertj.core.api.Assertions.assertThat(controller.decideModel(metadata,2,2).reason()).isEqualTo("BUDGET_EXHAUSTED");
    }

}
