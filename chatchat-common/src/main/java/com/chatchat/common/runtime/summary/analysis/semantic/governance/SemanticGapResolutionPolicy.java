package com.chatchat.common.runtime.summary.analysis.semantic.governance;

import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticEvidenceGapContract;
import java.util.LinkedHashMap;
import java.util.Map;

/** Domain-neutral idempotency and termination policy for repeated semantic gaps. */
public final class SemanticGapResolutionPolicy {

    public static final String SCHEMA_VERSION = "semantic_gap_resolution.v1";
    public static final int DEFAULT_MAX_ATTEMPTS = 2;

    public enum TerminalReason {
        NONE,
        NO_NEW_EVIDENCE,
        CAPABILITY_UNCHANGED,
        MAX_REPAIR_ATTEMPTS_REACHED
    }

    public record State(String gapFingerprint,
                        int attemptCount,
                        String evidenceVersion,
                        String capabilityVersion,
                        SemanticEvidenceGapContract.Route lastResolution,
                        TerminalReason terminalReason) {
        public State {
            gapFingerprint = text(gapFingerprint);
            attemptCount = Math.max(1, attemptCount);
            evidenceVersion = text(evidenceVersion);
            capabilityVersion = text(capabilityVersion);
            lastResolution = lastResolution == null
                ? SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS : lastResolution;
            terminalReason = terminalReason == null ? TerminalReason.NONE : terminalReason;
        }

        public boolean terminal() {
            return terminalReason != TerminalReason.NONE;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("gapFingerprint", gapFingerprint);
            value.put("attemptCount", attemptCount);
            value.put("evidenceVersion", evidenceVersion);
            value.put("capabilityVersion", capabilityVersion);
            value.put("lastResolution", lastResolution.name());
            value.put("terminalReason", terminalReason.name());
            value.put("terminal", terminal());
            return Map.copyOf(value);
        }
    }

    public State evaluate(SemanticEvidenceGapContract.Gap gap,
                          String evidenceVersion,
                          String capabilityVersion,
                          State previous,
                          int maxAttempts) {
        String fingerprint = gap == null ? "" : gap.gapId();
        int attempts = previous == null ? 1 : previous.attemptCount() + 1;
        int limit = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        TerminalReason terminal = TerminalReason.NONE;
        if (previous != null && previous.gapFingerprint().equals(fingerprint)) {
            if (attempts > limit) terminal = TerminalReason.MAX_REPAIR_ATTEMPTS_REACHED;
            else if (gap.route() == SemanticEvidenceGapContract.Route.RETRIEVE_MORE
                && previous.evidenceVersion().equals(text(evidenceVersion))) {
                terminal = TerminalReason.NO_NEW_EVIDENCE;
            } else if (gap.route() == SemanticEvidenceGapContract.Route.REPLAN
                && previous.capabilityVersion().equals(text(capabilityVersion))) {
                terminal = TerminalReason.CAPABILITY_UNCHANGED;
            }
        }
        return new State(fingerprint, attempts, evidenceVersion, capabilityVersion,
            terminal == TerminalReason.NONE ? gap.route()
                : SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS, terminal);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
