package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.AdaptiveAnalysisController.Decision;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import java.util.List;
import java.util.Optional;

/** Durable revision gate. Reservations commit before work; observations commit before wake consumption. */
public interface AnalysisProgressPort {
    State start(KernelDataScope scope, int maxRounds);
    State observe(AnalysisContext context, AnalysisExecutionOutcome outcome, List<EvidenceGap> gaps,
                  int round, Decision decision);
    State reserveRecovery(KernelDataScope scope, long expectedRevision);
    void stop(KernelDataScope scope, String reason);
    Optional<State> state(KernelDataScope scope);

    record State(boolean admitted, int rounds, int maxRounds, long revision,
                 long consumedRevision, String action, String reason) {}
}
