package com.chatchat.agents.orchestration.analysis.contract;

import java.util.List;
import java.util.Map;

/**
 * Source-neutral reasoning progression for decision-useful analytical reports.
 *
 * <p>The arc is an analysis affordance, not an evidence admission checklist. Stages after
 * observation may contain model reasoning as long as it remains bound to observed evidence and
 * is not represented as a producer-returned fact.</p>
 */
public final class AnalyticalReasoningArcContract {

    public static final String SCHEMA_VERSION = "analytical_reasoning_arc.v1";

    private static final List<String> STAGES = List.of(
        "OBSERVED_FACTS",
        "DERIVED_INDICATORS",
        "CROSS_VALIDATION",
        "PATTERN_RECOGNITION",
        "BUSINESS_INTERPRETATION",
        "HYPOTHESES",
        "SCENARIO_ANALYSIS",
        "RISKS",
        "DATA_GAPS",
        "NEXT_ACTIONS"
    );

    private AnalyticalReasoningArcContract() { }

    public static Map<String, Object> toMap() {
        return Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "stages", STAGES,
            "authority", "MODEL_REASONING_GUIDANCE_ONLY",
            "governanceMode", "CLASSIFY_AND_TRACE_DO_NOT_SUPPRESS_ANALYSIS"
        );
    }

    public static String promptSection() {
        return """
            Analytical reasoning arc (analytical_reasoning_arc.v1):
            - Optional analytical lenses: observed facts; derived indicators; cross-validation;
              pattern recognition; business interpretation;
              hypotheses; scenario analysis; risks; data gaps; next actions.
            - They describe desired depth, not a mandatory sequence, outline or checklist. Based on
              the question, Agent role, knowledge and evidence, select, combine, reorder or omit them.
              Do not manufacture sections merely to cover a lens.
            - Design the report and reasoning yourself. Do not stop at field transcription merely
              because interpretation, hypotheses or scenarios contain model reasoning.
            - Evidence governance traces facts and calculations and distinguishes model reasoning
              from observations; it must not convert "not directly proven" into "analysis forbidden".
            - "Cannot prove" does not mean "cannot analyze". It means an interpretation,
              hypothesis or scenario must remain recognizable as model reasoning rather than be
              rewritten as an observed fact.
            - Connect what you use: derive indicators from facts, test agreement or conflict, keep
              patterns within the observed sample and period, develop useful hypotheses and conditional
              scenarios, and ground actions in analysis. Do not convert a period pattern into a
              persistent trait, an outcome into its cause, or a hypothesis into a confirmed event.
            - Mention data gaps only when they materially affect the answer.
            """;
    }
}
