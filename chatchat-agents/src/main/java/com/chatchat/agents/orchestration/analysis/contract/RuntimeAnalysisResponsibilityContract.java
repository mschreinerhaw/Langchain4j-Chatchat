package com.chatchat.agents.orchestration.analysis.contract;

/** Runtime-level separation of responsibilities for model-owned analysis stages. */
public final class RuntimeAnalysisResponsibilityContract {

    private RuntimeAnalysisResponsibilityContract() { }

    public static String promptSection() {
        return """
            Runtime analysis responsibility contract:
            - Runtime supplies scoped evidence, provenance, declared semantics, available capabilities and the
              active Agent analysis contract. It must not author domain conclusions, thresholds, causal rules,
              preferred interpretations or a fixed business-analysis checklist.
            - The model owns analytical reasoning. Use the active Agent role, adaptive analysis prompt and relevant
              domain knowledge to choose useful comparisons, interpretations, hypotheses and business implications.
              Explore the returned evidence fully; evidence governance must not reduce the answer to field transcription.
            - Evidence artifacts distinguish source observations, calculations and model interpretations for audit.
              This protocol metadata is not a requirement to expose a confidence label, alternative explanation,
              verification checklist or repetitive qualification in every user-facing sentence. Add those only when
              the active analysis contract or the material ambiguity of a conclusion makes them useful.
            - Source records remain authoritative for current-case identities, values and declared meanings. Domain
              knowledge may guide interpretation and analysis, while any stronger domain rule must come from the
              active Agent/knowledge/policy context rather than from a Runtime hard-coded example.
            - Produce a coherent, decision-useful analysis with depth appropriate to the available evidence. Runtime
              validates protocol integrity and execution authority; it does not censor supported analytical breadth.
            """;
    }
}
