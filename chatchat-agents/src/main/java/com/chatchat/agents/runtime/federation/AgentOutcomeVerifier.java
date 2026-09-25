package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Treats every provider response as an untrusted candidate protocol. */
@Component
public class AgentOutcomeVerifier {
    public Verification verify(AgentDescriptor provider, AgentExecutionRequest request,
                               AgentExecutionOutcome outcome) {
        if (outcome == null) return Verification.reject("AGENT_EMPTY_RESPONSE", "Agent returned no outcome");
        if (!request.executionId().equals(outcome.executionId())) {
            return Verification.reject("AGENT_EXECUTION_ID_MISMATCH", "Agent changed the execution identity");
        }
        if (!outcome.providerAgentId().isBlank() && !provider.agentId().equals(outcome.providerAgentId())) {
            return Verification.reject("AGENT_PROVIDER_ID_MISMATCH", "Agent response provider identity mismatched");
        }
        if (!outcome.successful()) return Verification.accept();
        if (outcome.claims().isEmpty() && outcome.artifacts().isEmpty()) {
            return Verification.reject("AGENT_RESULT_EMPTY", "Successful outcome contains no claims or artifacts");
        }
        if (request.constraints().citeEvidence()) {
            Set<String> available = new HashSet<>();
            request.evidence().evidence().forEach(value -> available.add(value.evidenceId()));
            for (AgentExecutionOutcome.GroundedClaim claim : outcome.claims()) {
                if (claim.evidenceIds().isEmpty() && request.constraints().rejectUnsupportedClaims()) {
                    return Verification.reject("AGENT_CLAIM_UNGROUNDED", "Claim has no evidence references");
                }
                if (!available.containsAll(claim.evidenceIds())) {
                    return Verification.reject("AGENT_EVIDENCE_REFERENCE_INVALID",
                        "Claim references evidence outside the Runtime bundle");
                }
            }
        }
        return Verification.accept();
    }

    public record Verification(boolean accepted, String code, String message) {
        static Verification accept() { return new Verification(true, "", ""); }
        static Verification reject(String code, String message) { return new Verification(false, code, message); }
    }
}
