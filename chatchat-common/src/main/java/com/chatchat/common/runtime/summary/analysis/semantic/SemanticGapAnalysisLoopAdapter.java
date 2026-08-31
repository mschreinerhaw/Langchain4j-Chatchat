package com.chatchat.common.runtime.summary.analysis.semantic;

import com.chatchat.common.runtime.summary.analysis.AnalysisLoopContract;

import java.util.ArrayList;
import java.util.List;

/** Bridges semantic rejection gaps into the existing evidence-coverage retrieval contract. */
public final class SemanticGapAnalysisLoopAdapter {

    public AnalysisLoopContract.GapRequest toGapRequest(SemanticEvidenceGapContract.Gap gap) {
        if (gap == null || gap.route() == SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS) return null;
        List<String> capabilities = new ArrayList<>();
        if (!gap.requiredCapabilityId().isBlank()) capabilities.add(gap.requiredCapabilityId());
        if (gap.requiredOperation() != null) capabilities.add(gap.requiredOperation().name());
        String goal = gap.route() == SemanticEvidenceGapContract.Route.REPLAN
            ? "Resolve the rejected claim through a compatible producer-declared capability"
            : "Retrieve evidence matching the rejected claim's declared semantic scope";
        return new AnalysisLoopContract.GapRequest(
            gap.gapId(), goal, List.copyOf(capabilities), gap.requiredTimeScope(),
            gap.requiredGrain(), AnalysisLoopContract.Criticality.CORE,
            String.join(",", gap.rejectionCodes().stream().sorted().toList()));
    }

    public List<AnalysisLoopContract.GapRequest> toGapRequests(
        List<SemanticEvidenceGapContract.Gap> gaps) {
        if (gaps == null || gaps.isEmpty()) return List.of();
        return gaps.stream().map(this::toGapRequest).filter(java.util.Objects::nonNull)
            .distinct().toList();
    }
}
