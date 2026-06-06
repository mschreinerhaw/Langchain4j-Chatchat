package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Tool parameter validator for validating input against parameter definitions
 *
 * Provides comprehensive validation including:
 * - Type checking
 * - Required/optional validation
 * - Value range validation
 * - String length validation
 * - Enum value validation
 * - Pattern matching
 */
@Slf4j
public class ToolParameterValidator {

    /**
     * Validation result containing success status and error messages
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;

        public static ValidationResult success() {
            return ValidationResult.builder()
                .valid(true)
                .errors(java.util.Collections.emptyList())
                .build();
        }

        public static ValidationResult failure(List<String> errors) {
            return ValidationResult.builder()
                .valid(false)
                .errors(errors)
                .build();
        }

        public static ValidationResult failure(String error) {
            return ValidationResult.builder()
                .valid(false)
                .errors(java.util.Collections.singletonList(error))
                .build();
        }
    }

    /**
     * Validate a single parameter value against its definition
     */
    public static ValidationResult validateParameter(
        ToolParameter paramDef,
        Object value) {

        java.util.List<String> errors = new java.util.ArrayList<>();

        // Check required
        if (paramDef.isRequired() && (value == null || "".equals(value))) {
            errors.add("Parameter '" + paramDef.getName() + "' is required");
            return ValidationResult.failure(errors);
        }

        // If optional and null, it's valid
        if (value == null) {
            return ValidationResult.success();
        }

        // Type validation
        switch (paramDef.getType().toLowerCase()) {
            case "string":
                if (!(value instanceof String)) {
                    errors.add("Parameter '" + paramDef.getName() +
                        "' must be a string, got " + value.getClass().getSimpleName());
                }
                validateString(paramDef, (String) value, errors);
                break;
            case "number":
                validateNumber(paramDef, value, errors);
                break;
            case "boolean":
                if (!(value instanceof Boolean) && !(value instanceof String)) {
                    errors.add("Parameter '" + paramDef.getName() +
                        "' must be boolean, got " + value.getClass().getSimpleName());
                }
                break;
            case "array":
                if (!(value instanceof java.util.List) && !(value.getClass().isArray())) {
                    errors.add("Parameter '" + paramDef.getName() +
                        "' must be an array, got " + value.getClass().getSimpleName());
                }
                break;
        }

        // Enum validation
        if (paramDef.getEnumValues() != null && paramDef.getEnumValues().length > 0) {
            boolean found = false;
            for (String enumVal : paramDef.getEnumValues()) {
                if (enumVal.equals(value.toString())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be one of: " + java.util.Arrays.toString(paramDef.getEnumValues()));
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    private static void validateString(ToolParameter paramDef, String value, java.util.List<String> errors) {
        // Min length
        if (paramDef.getMinLength() != null && value.length() < paramDef.getMinLength()) {
            errors.add("Parameter '" + paramDef.getName() +
                "' must be at least " + paramDef.getMinLength() + " characters");
        }

        // Max length
        if (paramDef.getMaxLength() != null && value.length() > paramDef.getMaxLength()) {
            errors.add("Parameter '" + paramDef.getName() +
                "' must be at most " + paramDef.getMaxLength() + " characters");
        }

        // Pattern
        if (paramDef.getPattern() != null) {
            if (!value.matches(paramDef.getPattern())) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' does not match required pattern: " + paramDef.getPattern());
            }
        }
    }

    private static void validateNumber(ToolParameter paramDef, Object value, java.util.List<String> errors) {
        Number num;
        try {
            if (value instanceof Number) {
                num = (Number) value;
            } else if (value instanceof String) {
                num = Double.parseDouble((String) value);
            } else {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be a number, got " + value.getClass().getSimpleName());
                return;
            }
        } catch (NumberFormatException e) {
            errors.add("Parameter '" + paramDef.getName() +
                "' is not a valid number: " + value);
            return;
        }

        // Minimum
        if (paramDef.getMinimum() != null) {
            if (num.doubleValue() < paramDef.getMinimum().doubleValue()) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be >= " + paramDef.getMinimum());
            }
        }

        // Maximum
        if (paramDef.getMaximum() != null) {
            if (num.doubleValue() > paramDef.getMaximum().doubleValue()) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be <= " + paramDef.getMaximum());
            }
        }

        // Exclusive minimum
        if (paramDef.getExclusiveMinimum() != null) {
            if (num.doubleValue() <= paramDef.getExclusiveMinimum().doubleValue()) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be > " + paramDef.getExclusiveMinimum());
            }
        }

        // Exclusive maximum
        if (paramDef.getExclusiveMaximum() != null) {
            if (num.doubleValue() >= paramDef.getExclusiveMaximum().doubleValue()) {
                errors.add("Parameter '" + paramDef.getName() +
                    "' must be < " + paramDef.getExclusiveMaximum());
            }
        }
    }

    /**
     * Validate all parameters in ToolInput against parameter definitions
     */
    public static ValidationResult validateToolInput(
        List<ToolParameter> paramDefs,
        ToolInput input) {

        java.util.List<String> errors = new java.util.ArrayList<>();

        if (paramDefs == null || paramDefs.isEmpty()) {
            return ValidationResult.success();
        }

        for (ToolParameter paramDef : paramDefs) {
            Object value = input.getParameter(paramDef.getName());
            ValidationResult result = validateParameter(paramDef, value);
            if (!result.isValid()) {
                errors.addAll(result.getErrors());
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }
}
