package com.chatchat.agents.orchestration.analysis.insight;

import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.InMemoryDatasetHandle;


import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes versioned semantic recipes without knowing any business field or entity name. */
public final class DeterministicInsightEngine {
    public static final String RESULT_VERSION = "deterministic_insights.v1";
    private static final int MAX_AGGREGATE_EVIDENCE_REFS = 200;

    public Result analyze(GovernanceIsolationScope scope, String datasetReference,
                          SemanticInsightContract contract, List<Map<String, Object>> records) {
        return analyze(scope, datasetReference, contract, new InMemoryDatasetHandle(records));
    }

    public Result analyze(GovernanceIsolationScope scope, String datasetReference,
                          SemanticInsightContract contract, DatasetHandle records) {
        if (contract == null) return Result.skipped("contract_not_supplied");
        if (scope == null || contract.tenantId() == null
            || !scope.tenantId().equals(contract.tenantId())) return Result.rejected("tenant_scope_mismatch");
        if (!"published".equalsIgnoreCase(contract.status())) return Result.rejected("contract_not_published");
        if (contract.fieldsBySemantic().isEmpty()) return Result.rejected("semantic_fields_missing");
        DatasetHandle safeRecords = records == null ? new InMemoryDatasetHandle(List.of()) : records;
        List<Finding> findings = new ArrayList<>();
        List<RecipeIssue> issues = new ArrayList<>();
        for (SemanticInsightContract.Recipe recipe : contract.recipes()) {
            List<String> validationIssues = SemanticInsightRecipeCatalog.validate(recipe);
            if (!validationIssues.isEmpty()) {
                issues.add(new RecipeIssue(recipe.id(), "recipe_configuration_invalid",
                    String.join("; ", validationIssues)));
                continue;
            }
            try {
                findings.addAll(execute(datasetReference, contract, recipe, safeRecords));
            } catch (RuntimeException ex) {
                issues.add(new RecipeIssue(recipe.id(), "recipe_rejected", safeMessage(ex)));
            }
        }
        return new Result(RESULT_VERSION, "executed", contract.contractId(), contract.version(),
            scope.toMap(), List.copyOf(findings), List.copyOf(issues));
    }

    private List<Finding> execute(String dataset, SemanticInsightContract contract,
                                  SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        String operator = required(recipe.operator(), "operator").toUpperCase(Locale.ROOT);
        return switch (operator) {
            case "SUM" -> List.of(sum(dataset, contract, recipe, records));
            case "TOP_N" -> List.of(topN(dataset, contract, recipe, records, false));
            case "CONTRIBUTION" -> List.of(topN(dataset, contract, recipe, records, true));
            case "CONCENTRATION" -> List.of(concentration(dataset, contract, recipe, records));
            case "RECONCILIATION" -> reconciliation(dataset, contract, recipe, records);
            case "OUTLIER_RATIO" -> outlierRatio(dataset, contract, recipe, records);
            case "TAG_MATCH" -> tagMatch(dataset, contract, recipe, records);
            case "BUNDLE_RECONCILIATION", "BUNDLE_RATIO" -> List.of();
            default -> throw new IllegalArgumentException("Unsupported generic operator: " + operator);
        };
    }

    /** Executes explicit cross-dataset formulas after every source has been independently governed. */
    public Result analyzeBundle(GovernanceIsolationScope scope, List<DatasetInput> datasets) {
        if (scope == null || datasets == null || datasets.isEmpty()) return Result.skipped("bundle_empty");
        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        List<SemanticInsightContract.Recipe> recipes = new ArrayList<>();
        List<RecipeIssue> issues = new ArrayList<>();
        for (DatasetInput dataset : datasets) {
            SemanticInsightContract contract = dataset.contract();
            if (contract == null) continue;
            if (contract.tenantId() == null || !scope.tenantId().equals(contract.tenantId())
                || !"published".equalsIgnoreCase(contract.status())) {
                issues.add(new RecipeIssue(contract.contractId(), "bundle_contract_rejected", "tenant or status mismatch"));
                continue;
            }
            List<SemanticInsightContract.Recipe> bundleRecipes = contract.recipes().stream()
                .filter(recipe -> recipe.operator() != null && recipe.operator().toUpperCase(Locale.ROOT).startsWith("BUNDLE_"))
                .toList();
            if (bundleRecipes.isEmpty()) continue;
            String alias = required(contract.datasetAlias(), "datasetAlias");
            for (SemanticInsightContract.Field field : contract.fields()) {
                if (!field.valid() || field.aggregation() == null || field.aggregation().isBlank()) continue;
                Aggregate aggregate = aggregate(dataset.reference(), dataset.records(), field);
                if (aggregate.value() == null) continue;
                String key = alias + "." + field.semantic();
                if (variables.putIfAbsent(key, aggregate.value()) != null) {
                    issues.add(new RecipeIssue(key, "duplicate_bundle_variable", key));
                    variables.remove(key);
                    evidence.remove(key);
                } else evidence.put(key, aggregate.evidenceRefs());
            }
            recipes.addAll(bundleRecipes);
        }
        List<Finding> findings = new ArrayList<>();
        java.util.Set<String> executedIds = new java.util.LinkedHashSet<>();
        for (SemanticInsightContract.Recipe recipe : recipes) {
            if (!executedIds.add(recipe.id())) continue;
            List<String> validationIssues = SemanticInsightRecipeCatalog.validate(recipe);
            if (!validationIssues.isEmpty()) {
                issues.add(new RecipeIssue(recipe.id(), "bundle_recipe_configuration_invalid",
                    String.join("; ", validationIssues)));
                continue;
            }
            try {
                String operator = recipe.operator().toUpperCase(Locale.ROOT);
                String leftExpression = parameter(recipe, "leftExpression");
                String rightExpression = parameter(recipe, "rightExpression");
                BigDecimal left = SafeNumericExpression.evaluate(leftExpression, variables);
                BigDecimal right = SafeNumericExpression.evaluate(rightExpression, variables);
                BigDecimal value;
                String type;
                Map<String, Object> details = new LinkedHashMap<>();
                if ("BUNDLE_RATIO".equals(operator)) {
                    if (right.compareTo(BigDecimal.ZERO) == 0) throw new IllegalArgumentException("Bundle ratio division by zero");
                    value = left.divide(right, 8, RoundingMode.HALF_UP);
                    type = "bundle_ratio";
                    details.put("numerator", left); details.put("denominator", right);
                } else {
                    BigDecimal tolerance = number(recipe.parameters().get("tolerance"));
                    if (tolerance == null) tolerance = new BigDecimal("0.01");
                    value = left.subtract(right);
                    boolean matched = value.abs().compareTo(tolerance.abs()) <= 0;
                    type = matched ? "bundle_reconciliation_match" : "bundle_reconciliation_mismatch";
                    details.put("left", left); details.put("right", right);
                    details.put("difference", value); details.put("tolerance", tolerance); details.put("matched", matched);
                }
                List<String> refs = identifiers(leftExpression + " " + rightExpression).stream()
                    .flatMap(key -> evidence.getOrDefault(key, List.of()).stream()).distinct().toList();
                findings.add(finding(recipe, type, value,
                    "BUNDLE_RATIO".equals(operator) ? "ratio" : null,
                    leftExpression + ("BUNDLE_RATIO".equals(operator) ? " / " : " - (")
                        + rightExpression + ("BUNDLE_RATIO".equals(operator) ? "" : ")"), refs, details));
            } catch (RuntimeException ex) {
                issues.add(new RecipeIssue(recipe.id(), "bundle_recipe_rejected", safeMessage(ex)));
            }
        }
        return new Result(RESULT_VERSION, "executed", "bundle", "1", scope.toMap(),
            List.copyOf(findings), List.copyOf(issues));
    }

    private Aggregate aggregate(String dataset, DatasetHandle records,
                                SemanticInsightContract.Field field) {
        String aggregation = field.aggregation().toUpperCase(Locale.ROOT);
        BigDecimal[] sum = {BigDecimal.ZERO};
        IndexedNumber[] first = {null}, last = {null}, min = {null}, max = {null};
        List<String> sumRefs = new ArrayList<>();
        forEach(records, (index, record) -> {
            BigDecimal value = number(record.get(field.field()));
            if (value == null) return;
            IndexedNumber item = new IndexedNumber(index, value);
            if (first[0] == null) first[0] = item;
            last[0] = item;
            if (min[0] == null || value.compareTo(min[0].value()) < 0) min[0] = item;
            if (max[0] == null || value.compareTo(max[0].value()) > 0) max[0] = item;
            sum[0] = sum[0].add(value);
            if (sumRefs.size() < MAX_AGGREGATE_EVIDENCE_REFS)
                sumRefs.add(ref(dataset, index, field.field()));
        });
        if (first[0] == null) return new Aggregate(null, List.of());
        IndexedNumber selected = switch (aggregation) {
            case "FIRST" -> first[0];
            case "LAST" -> last[0];
            case "MAX" -> max[0];
            case "MIN" -> min[0];
            case "SUM" -> null;
            default -> throw new IllegalArgumentException("Unsupported aggregation: " + aggregation);
        };
        if ("SUM".equals(aggregation)) return new Aggregate(sum[0], List.copyOf(sumRefs));
        return new Aggregate(selected.value(), List.of(ref(dataset, selected.index(), field.field())));
    }

    private List<String> identifiers(String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("[A-Za-z_][A-Za-z0-9_.]*").matcher(expression == null ? "" : expression);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        return result.stream().distinct().toList();
    }

    private Finding sum(String dataset, SemanticInsightContract contract,
                        SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        String semantic = parameter(recipe, "metric");
        SemanticInsightContract.Field field = field(contract, semantic);
        BigDecimal[] total = {BigDecimal.ZERO};
        long[] contributingRecords = {0};
        List<String> refs = new ArrayList<>();
        forEach(records, (index, record) -> {
            BigDecimal value = number(record.get(field.field()));
            if (value == null) return;
            total[0] = total[0].add(value);
            contributingRecords[0]++;
            if (refs.size() < MAX_AGGREGATE_EVIDENCE_REFS)
                refs.add(ref(dataset, index, field.field()));
        });
        return finding(recipe, "aggregate", total[0], field.unit(),
            semantic + " = sum(" + field.field() + ")", refs,
            Map.of("recordCount", contributingRecords[0], "evidenceRefsTruncated",
                contributingRecords[0] > refs.size()));
    }

    private Finding topN(String dataset, SemanticInsightContract contract,
                         SemanticInsightContract.Recipe recipe, DatasetHandle records,
                         boolean contribution) {
        SemanticInsightContract.Field valueField = field(contract, parameter(recipe, "valueMetric"));
        SemanticInsightContract.Field groupField = field(contract, parameter(recipe, "groupBy"));
        int limit = boundedInt(recipe.parameters().get("topN"), 3, 1, 20);
        boolean absolute = truthy(recipe.parameters().get("absoluteValues"));
        BigDecimal[] denominatorHolder = {BigDecimal.ZERO};
        java.util.PriorityQueue<RowValue> heap = new java.util.PriorityQueue<>(
            Comparator.comparing(RowValue::rankingValue));
        forEach(records, (index, record) -> {
            BigDecimal raw = number(record.get(valueField.field()));
            if (raw == null) return;
            RowValue item = new RowValue(index, displayValue(groupField, record.get(groupField.field())), raw,
                absolute ? raw.abs() : raw);
            denominatorHolder[0] = denominatorHolder[0].add(item.rankingValue());
            heap.offer(item);
            if (heap.size() > limit) heap.poll();
        });
        BigDecimal denominator = denominatorHolder[0];
        List<RowValue> top = heap.stream().sorted(Comparator.comparing(RowValue::rankingValue).reversed()).toList();
        BigDecimal topTotal = top.stream().map(RowValue::rankingValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal result = contribution && denominator.compareTo(BigDecimal.ZERO) != 0
            ? topTotal.divide(denominator, 8, RoundingMode.HALF_UP) : topTotal;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("topN", limit);
        details.put("valueUnit", valueField.unit() == null ? "" : valueField.unit());
        details.put("items", top.stream().map(item -> Map.of(
            "entity", item.entity(), "value", item.rawValue(),
            "ratio", denominator.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : item.rankingValue().divide(denominator, 8, RoundingMode.HALF_UP))).toList());
        details.put("denominator", denominator);
        return finding(recipe, contribution ? "contribution" : "top_n", result,
            contribution ? "ratio" : valueField.unit(),
            contribution ? "top(" + limit + "," + valueField.semantic() + ") / sum(" + valueField.semantic() + ")"
                : "top(" + limit + "," + valueField.semantic() + ")",
            top.stream().map(item -> ref(dataset, item.index(), valueField.field())).toList(), details);
    }

    private Finding concentration(String dataset, SemanticInsightContract contract,
                                  SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        Map<String, Object> values = new LinkedHashMap<>(recipe.parameters());
        values.putIfAbsent("absoluteValues", true);
        SemanticInsightContract.Recipe delegated = new SemanticInsightContract.Recipe(
            recipe.id(), "CONTRIBUTION", recipe.label(), Collections.unmodifiableMap(values),
            recipe.presentation());
        Finding result = topN(dataset, contract, delegated, records, true);
        return new Finding(result.id(), "concentration", result.label(), result.value(), result.unit(),
            result.calculation(), result.evidenceRefs(), result.details());
    }

    private List<Finding> reconciliation(String dataset, SemanticInsightContract contract,
                                         SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        String leftExpression = parameter(recipe, "leftExpression");
        String rightExpression = parameter(recipe, "rightExpression");
        BigDecimal tolerance = number(recipe.parameters().get("tolerance"));
        if (tolerance == null) tolerance = new BigDecimal("0.01");
        final BigDecimal acceptedTolerance = tolerance;
        boolean mismatchOnly = "mismatch".equalsIgnoreCase(text(recipe.parameters().get("emitWhen")));
        List<Finding> findings = new ArrayList<>();
        int maxFindings = maxFindings(recipe);
        forEachUntil(records, (index, record) -> {
            Map<String, BigDecimal> variables = variables(contract, record);
            BigDecimal left = SafeNumericExpression.evaluate(leftExpression, variables);
            BigDecimal right = SafeNumericExpression.evaluate(rightExpression, variables);
            BigDecimal difference = left.subtract(right);
            boolean matched = difference.abs().compareTo(acceptedTolerance.abs()) <= 0;
            if (mismatchOnly && matched) return false;
            int recordIndex = index;
            List<String> refs = variables.keySet().stream().map(semantic ->
                ref(dataset, recordIndex, field(contract, semantic).field())).distinct().toList();
            findings.add(finding(recipe, matched ? "reconciliation_match" : "reconciliation_mismatch",
                difference, null, leftExpression + " - (" + rightExpression + ")", refs,
                Map.of("record", index + 1, "left", left, "right", right,
                    "difference", difference, "tolerance", acceptedTolerance, "matched", matched)));
            return findings.size() >= maxFindings;
        });
        return findings;
    }

    private List<Finding> outlierRatio(String dataset, SemanticInsightContract contract,
                                       SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        SemanticInsightContract.Field numerator = field(contract, parameter(recipe, "numerator"));
        SemanticInsightContract.Field denominator = field(contract, parameter(recipe, "denominator"));
        SemanticInsightContract.Field entity = field(contract, parameter(recipe, "entity"));
        BigDecimal threshold = requiredNumber(recipe.parameters().get("threshold"), "threshold");
        boolean absolute = !Boolean.FALSE.equals(recipe.parameters().get("absolute"));
        String comparator = text(recipe.parameters().getOrDefault("comparator", ">"));
        List<Finding> result = new ArrayList<>();
        int maxFindings = maxFindings(recipe);
        forEachUntil(records, (index, record) -> {
            BigDecimal top = number(record.get(numerator.field()));
            BigDecimal bottom = number(record.get(denominator.field()));
            if (top == null || bottom == null || bottom.compareTo(BigDecimal.ZERO) == 0) return false;
            BigDecimal ratio = top.divide(bottom, 8, RoundingMode.HALF_UP);
            BigDecimal compared = absolute ? ratio.abs() : ratio;
            if (!compare(compared, threshold, comparator)) return false;
            result.add(finding(recipe, "outlier", ratio, "ratio",
                numerator.semantic() + " / " + denominator.semantic(),
                List.of(ref(dataset, index, numerator.field()), ref(dataset, index, denominator.field())),
                Map.of("record", index + 1, "entity", displayValue(entity, record.get(entity.field())),
                    "threshold", threshold, "comparator", comparator)));
            return result.size() >= maxFindings;
        });
        return result;
    }

    private List<Finding> tagMatch(String dataset, SemanticInsightContract contract,
                                   SemanticInsightContract.Recipe recipe, DatasetHandle records) {
        SemanticInsightContract.Field field = field(contract, parameter(recipe, "field"));
        String expected = parameter(recipe, "value");
        String mode = text(recipe.parameters().getOrDefault("matchMode", "equals")).toLowerCase(Locale.ROOT);
        List<Finding> result = new ArrayList<>();
        int maxFindings = maxFindings(recipe);
        forEachUntil(records, (index, record) -> {
            String actual = text(record.get(field.field()));
            if (actual == null) return false;
            boolean matched = switch (mode) {
                case "starts_with" -> actual.startsWith(expected);
                case "contains" -> actual.contains(expected);
                case "equals" -> actual.equals(expected);
                default -> throw new IllegalArgumentException("Unsupported match mode");
            };
            if (matched) result.add(finding(recipe, "tag", null, null,
                field.semantic() + " " + mode + " configured value", List.of(ref(dataset, index, field.field())),
                Map.of("record", index + 1, "matchedValue", displayValue(field, actual),
                    "tag", text(recipe.parameters().getOrDefault("tag", recipe.id())))));
            return result.size() >= maxFindings;
        });
        return result;
    }

    private void forEach(DatasetHandle records, IndexedRecordConsumer consumer) {
        records.scan(1_000, page -> {
            for (int offset = 0; offset < page.rows().size(); offset++)
                consumer.accept(Math.toIntExact(page.offset() + offset), page.rows().get(offset));
        });
    }

    private void forEachUntil(DatasetHandle records, StoppableIndexedRecordConsumer consumer) {
        try {
            records.scan(1_000, page -> {
                for (int offset = 0; offset < page.rows().size(); offset++)
                    if (consumer.accept(Math.toIntExact(page.offset() + offset), page.rows().get(offset)))
                        throw StopScan.INSTANCE;
            });
        } catch (StopScan complete) { /* bounded result obtained */ }
    }

    private Map<String, BigDecimal> variables(SemanticInsightContract contract, Map<String, Object> record) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        contract.fieldsBySemantic().forEach((semantic, field) -> {
            BigDecimal value = number(record.get(field.field()));
            if (value != null) values.put(semantic, value);
        });
        return values;
    }

    private Finding finding(SemanticInsightContract.Recipe recipe, String type, BigDecimal value, String unit,
                            String calculation, List<String> refs, Map<String, Object> details) {
        Map<String, Object> governedDetails = new LinkedHashMap<>(details);
        governedDetails.put("presentation", recipe.presentation().toMap());
        return new Finding(required(recipe.id(), "recipe id"), type,
            recipe.label() == null || recipe.label().isBlank() ? recipe.id() : recipe.label(),
            value, unit, calculation, List.copyOf(refs), Map.copyOf(governedDetails));
    }

    private SemanticInsightContract.Field field(SemanticInsightContract contract, String semantic) {
        SemanticInsightContract.Field result = contract.fieldsBySemantic().get(semantic);
        if (result == null) throw new IllegalArgumentException("Unknown semantic field: " + semantic);
        return result;
    }

    private String parameter(SemanticInsightContract.Recipe recipe, String key) {
        return required(text(recipe.parameters().get(key)), key);
    }
    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
    private BigDecimal requiredNumber(Object value, String label) {
        BigDecimal number = number(value);
        if (number == null) throw new IllegalArgumentException(label + " must be numeric");
        return number;
    }
    private BigDecimal number(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value).replace(",", "").trim()); }
        catch (NumberFormatException ex) { return null; }
    }
    private boolean truthy(Object value) { return SemanticInsightContract.truthy(value); }
    private String text(Object value) { return SemanticInsightContract.text(value); }
    private int boundedInt(Object value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(value)))); }
        catch (Exception ignored) { return fallback; }
    }
    private int maxFindings(SemanticInsightContract.Recipe recipe) {
        return boundedInt(recipe.parameters().get("maxFindings"), 20, 1, 100);
    }
    private String displayValue(SemanticInsightContract.Field field, Object value) {
        return field != null && field.sensitive() ? "***" : String.valueOf(value);
    }
    private boolean compare(BigDecimal left, BigDecimal right, String comparator) {
        int value = left.compareTo(right);
        return switch (comparator) { case ">" -> value > 0; case ">=" -> value >= 0;
            case "<" -> value < 0; case "<=" -> value <= 0; case "==" -> value == 0;
            default -> throw new IllegalArgumentException("Unsupported comparator"); };
    }
    private String ref(String dataset, int index, String field) {
        return (dataset == null || dataset.isBlank() ? "result" : dataset) + ".records[" + (index + 1) + "]." + field;
    }
    private String safeMessage(RuntimeException ex) {
        String value = ex.getMessage();
        return value == null || value.isBlank() ? ex.getClass().getSimpleName() : value;
    }

    private record RowValue(int index, String entity, BigDecimal rawValue, BigDecimal rankingValue) {}
    private record IndexedNumber(int index, BigDecimal value) {}
    private record Aggregate(BigDecimal value, List<String> evidenceRefs) {}
    @FunctionalInterface private interface IndexedRecordConsumer {
        void accept(int index, Map<String, Object> record);
    }
    @FunctionalInterface private interface StoppableIndexedRecordConsumer {
        boolean accept(int index, Map<String, Object> record);
    }
    private static final class StopScan extends RuntimeException {
        static final StopScan INSTANCE = new StopScan();
        private StopScan() { super(null, null, false, false); }
    }
    public record DatasetInput(String reference, SemanticInsightContract contract,
                               DatasetHandle records) {
        public DatasetInput(String reference, SemanticInsightContract contract,
                            List<Map<String, Object>> records) {
            this(reference, contract, new InMemoryDatasetHandle(records));
        }
        public DatasetInput {
            records = records == null ? new InMemoryDatasetHandle(List.of()) : records;
        }
    }
    public record Finding(String id, String type, String label, BigDecimal value, String unit,
                          String calculation, List<String> evidenceRefs, Map<String, Object> details) {}
    public record RecipeIssue(String recipeId, String code, String message) {}
    public record Result(String schemaVersion, String status, String contractId, String contractVersion,
                         Map<String, Object> isolationScope, List<Finding> findings, List<RecipeIssue> issues) {
        static Result skipped(String reason) { return empty("skipped", reason); }
        static Result rejected(String reason) { return empty("rejected", reason); }
        private static Result empty(String status, String reason) {
            return new Result(RESULT_VERSION, status, null, null, Map.of(), List.of(),
                List.of(new RecipeIssue(null, reason, reason)));
        }
        public boolean executed() { return "executed".equals(status); }
        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schemaVersion", schemaVersion); values.put("status", status);
            values.put("contractId", contractId); values.put("contractVersion", contractVersion);
            values.put("isolationScope", isolationScope); values.put("findings", findings); values.put("issues", issues);
            return Collections.unmodifiableMap(values);
        }
    }
}
