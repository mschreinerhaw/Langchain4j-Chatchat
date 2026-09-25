package com.chatchat.api.runtime;

import com.chatchat.common.runtime.analysis.evidence.ComputationEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic arithmetic over already verified, complete structured evidence. */
@Component
public class VerifiedEvidenceComputationOperator implements AnalysisCapabilityOperator {
    public static final String OPERATION = "runtime.analysis.metricOperation";
    public static final String FIELD = "runtime.analysis.metricField";
    private static final Set<String> OPERATIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    private final ObjectMapper mapper;
    public VerifiedEvidenceComputationOperator(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
    @Override public boolean available(AnalysisContext context) {
        return context != null && context.attributes().get(OPERATION) instanceof String value && !value.isBlank();
    }

    @Override
    public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        String operation = String.valueOf(context.attributes().get(OPERATION)).trim().toUpperCase(Locale.ROOT);
        if (!OPERATIONS.contains(operation)) return failed("Unsupported deterministic metric operation");
        String field = context.attributes().get(FIELD) instanceof String text ? text.trim() : "";
        if (!"COUNT".equals(operation) && (field.isBlank() || field.length() > 128))
            return failed("A bounded numeric field is required");
        Object supplied = context.attributes().get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE);
        if (!(supplied instanceof EvidenceBundle bundle)) return failed("Verified source evidence is required");
        List<StructuredDataEvidence> sources = bundle.evidence().stream()
            .filter(StructuredDataEvidence.class::isInstance)
            .map(StructuredDataEvidence.class::cast)
            .toList();
        if (sources.size() != 1 || !scope.tenantId().equals(sources.get(0).attributes().get("tenantId")))
            return failed("Exactly one same-tenant structured result is required");
        StructuredDataEvidence source = sources.get(0);
        try {
            JsonNode data = mapper.readTree(source.content()).path("data");
            JsonNode rows = data.path("rows");
            if (!data.path("complete").asBoolean(false) || !rows.isArray()
                || rows.size() > 100 || rows.size() != source.rows())
                return failed("Source rows are incomplete or unverified");
            BigDecimal value;
            if ("COUNT".equals(operation)) value = BigDecimal.valueOf(source.rows());
            else {
                if (rows.isEmpty()) return failed("Numeric aggregation requires at least one row");
                List<BigDecimal> values = new ArrayList<>();
                for (JsonNode row : rows) {
                    JsonNode item = row.path(field);
                    if (!item.isNumber()) return failed("Metric field must be numeric in every row");
                    values.add(item.decimalValue());
                }
                value = switch (operation) {
                    case "SUM" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    case "AVG" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);
                    case "MIN" -> values.stream().min(BigDecimal::compareTo).orElseThrow();
                    case "MAX" -> values.stream().max(BigDecimal::compareTo).orElseThrow();
                    default -> throw new IllegalStateException("Unsupported metric");
                };
            }
            String content = mapper.writeValueAsString(Map.of("operation", operation, "field", field,
                "value", value, "sourceEvidenceId", source.evidenceId()));
            var evidence = new ComputationEvidence(UUID.randomUUID().toString(),
                operation + "(" + ("COUNT".equals(operation) ? "*" : field) + ")",
                List.of(source.evidenceId()), content,
                Map.of("tenantId", scope.tenantId(), "remoteProjection",
                    Map.of("operation", operation, "field", field, "value", value)));
            return new WorkflowExecutionResult(List.of(evidence), Map.of(), List.of());
        } catch (Exception invalid) {
            return failed("Structured metric result cannot be verified");
        }
    }

    private WorkflowExecutionResult failed(String reason) {
        return new WorkflowExecutionResult(List.of(), Map.of(), List.of(reason));
    }
}
