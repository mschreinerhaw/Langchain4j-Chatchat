package com.chatchat.api.runtime;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
/** Financial answers exist only in an explicit test extension, outside production Runtime. */
public final class FixtureDecimalCodingVerifier implements CodingResultVerifier {
    private final McpPythonControlPlanePort python;
    private final ObjectMapper mapper;
    public FixtureDecimalCodingVerifier(McpPythonControlPlanePort python, ObjectMapper mapper) { this.python=python; this.mapper=mapper; }
    public String id() { return "fixture-decimal-sum"; }
    public String version() { return "v1"; }
    public static CodingValidationPlanService.Plan plan(String source, String result, String unique, boolean empty) {
        return new CodingValidationPlanService.Plan("fixture-decimal-sum", "v1", Map.of("sourceAmountField",source,
            "resultAmountField",result,"uniqueSourceField",unique==null?"":unique,"allowEmptyResult",empty));
    }
    private Plan rule(Map<String,Object> rules) {
        String unique=(String)rules.get("uniqueSourceField");
        return new Plan((String)rules.get("sourceAmountField"),(String)rules.get("resultAmountField"),
            unique.isBlank()?null:unique,(Boolean)rules.get("allowEmptyResult"));
    }
    public void validateRules(Map<String,Object> rules) { rule(rules); }
    public CodingEvidenceCollector.Validation verify(KernelDataScope scope, CodingInputSnapshotService.Snapshot input,
            Map<String,Object> rules,List<Map<String,Object>> rows) { return validate(scope,input,rule(rules),rows); }
    @TestConfiguration(proxyBeanMethods=false)
    public static class Configuration {
        @Bean CodingResultVerifier fixtureDecimalVerifier(McpPythonControlPlanePort python,ObjectMapper mapper) {
            return new FixtureDecimalCodingVerifier(python,mapper);
        }
    }
    public record Plan(String sourceAmountField, String resultAmountField,
                       String uniqueSourceField, boolean allowEmptyResult) {
        public Plan {
            if (!field(sourceAmountField) || !field(resultAmountField)
                    || uniqueSourceField != null && !field(uniqueSourceField))
                throw new IllegalArgumentException("Validation fields must be simple named columns");
        }
        private static boolean field(String value) {
            return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
        }
    }

    private CodingEvidenceCollector.Validation validate(KernelDataScope scope,
            CodingInputSnapshotService.Snapshot input, Plan plan, List<Map<String, Object>> result) {
        byte[] bytes = python.downloadDataFile(scope.tenantId(), scope.userId(), input.fileId());
        if (!sha(bytes).equals(input.sha256()))
            throw new SecurityException("Independent verifier input changed after snapshot capture");
        BigDecimal expected = BigDecimal.ZERO;
        HashSet<String> unique = new HashSet<>();
        long inputRows = 0;
        boolean inputValid = true, uniqueValid = true;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            inputRows++;
            try {
                JsonNode row = mapper.readTree(line);
                JsonNode amount = row.path(plan.sourceAmountField());
                if (!amount.isNumber() && !amount.isTextual()) throw new IllegalArgumentException("Missing decimal source");
                expected = expected.add(new BigDecimal(amount.asText()));
                if (plan.uniqueSourceField() != null
                        && !unique.add(row.path(plan.uniqueSourceField()).asText("")))
                    uniqueValid = false;
            } catch (Exception invalid) { inputValid = false; }
        }
        if (inputRows != input.rowCount()) throw new SecurityException("Snapshot row count changed");
        boolean resultShape = result.size() == 1 || plan.allowEmptyResult() && result.isEmpty() && inputRows == 0;
        boolean amountMatches = false;
        if (result.size() == 1) {
            Object actual = result.get(0).get(plan.resultAmountField());
            try { amountMatches = actual != null && new BigDecimal(actual.toString()).compareTo(expected) == 0; }
            catch (NumberFormatException invalid) { amountMatches = false; }
        } else if (result.isEmpty() && plan.allowEmptyResult() && inputRows == 0) amountMatches = true;
        boolean passed = inputValid && uniqueValid && resultShape && amountMatches;
        return new CodingEvidenceCollector.Validation(passed, "runtime-decimal-sum", "v1",
            Map.of("inputDecimalValid", inputValid, "inputUnique", uniqueValid,
                "resultShapeValid", resultShape, "decimalReconciled", amountMatches,
                "inputRows", inputRows, "resultRows", result.size()));
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}