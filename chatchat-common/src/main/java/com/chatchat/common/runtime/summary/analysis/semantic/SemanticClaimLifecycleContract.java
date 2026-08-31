package com.chatchat.common.runtime.summary.analysis.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable revision history for a candidate claim; revisions are appended, never overwritten. */
public final class SemanticClaimLifecycleContract {

    public static final String SCHEMA_VERSION = "semantic_claim_lifecycle.v1";
    public static final String ADMISSION_VERSION = "semantic_claim_admission.v1";

    private SemanticClaimLifecycleContract() {
    }

    public enum State { PROPOSED, VALIDATING, ADMITTED, REJECTED, GAP_CREATED, RE_EVALUATED }

    public record Revision(String claimId,
                           String claimFingerprint,
                           int revision,
                           String parentClaimId,
                           String evidenceVersion,
                           String admissionVersion,
                           State state,
                           List<State> transitions,
                           List<String> rejectionCodes,
                           String semanticGapId) {
        public Revision {
            claimFingerprint = text(claimFingerprint);
            revision = Math.max(1, revision);
            parentClaimId = text(parentClaimId);
            evidenceVersion = text(evidenceVersion);
            admissionVersion = text(admissionVersion).isBlank() ? ADMISSION_VERSION : text(admissionVersion);
            state = state == null ? State.REJECTED : state;
            transitions = transitions == null ? List.of() : List.copyOf(transitions);
            rejectionCodes = rejectionCodes == null ? List.of() : rejectionCodes.stream().distinct().toList();
            semanticGapId = text(semanticGapId);
            claimId = text(claimId);
            if (claimId.isBlank()) claimId = "claim:" + fingerprint(claimFingerprint + "|" + revision + "|" + evidenceVersion);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("claimId", claimId);
            value.put("claimFingerprint", claimFingerprint);
            value.put("revision", revision);
            if (!parentClaimId.isBlank()) value.put("parentClaimId", parentClaimId);
            value.put("evidenceVersion", evidenceVersion);
            value.put("admissionVersion", admissionVersion);
            value.put("state", state.name());
            value.put("transitions", transitions.stream().map(Enum::name).toList());
            value.put("rejectionCodes", rejectionCodes);
            if (!semanticGapId.isBlank()) value.put("semanticGapId", semanticGapId);
            return Map.copyOf(value);
        }
    }

    public static Revision evolve(String claimFingerprint,
                                  String evidenceVersion,
                                  boolean admitted,
                                  List<String> rejectionCodes,
                                  String semanticGapId,
                                  Revision previous) {
        int revision = previous == null ? 1 : previous.revision() + 1;
        List<State> transitions = new ArrayList<>();
        transitions.add(previous == null ? State.PROPOSED : State.RE_EVALUATED);
        transitions.add(State.VALIDATING);
        transitions.add(admitted ? State.ADMITTED : State.REJECTED);
        State terminal = admitted ? State.ADMITTED : State.REJECTED;
        if (!admitted && semanticGapId != null && !semanticGapId.isBlank()) {
            transitions.add(State.GAP_CREATED);
            terminal = State.GAP_CREATED;
        }
        return new Revision("", claimFingerprint, revision,
            previous == null ? "" : previous.claimId(), evidenceVersion, ADMISSION_VERSION,
            terminal, transitions, rejectionCodes, semanticGapId);
    }

    public static String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
