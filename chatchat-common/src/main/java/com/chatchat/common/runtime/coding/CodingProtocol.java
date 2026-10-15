package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Shared wire constraints. JSON Schema validation is additionally required at the transport boundary. */
public final class CodingProtocol {
    public static final String VERSION = "runtime_os.coding.v1";
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private CodingProtocol() { }

    static void require(boolean condition, String message) {
        if (!condition) throw new CodingContractException(CodingErrorCode.CONTRACT_INVALID, message);
    }
    static void version(String value) {
        if (!VERSION.equals(value)) throw new CodingContractException(
            CodingErrorCode.VERSION_UNSUPPORTED, "Unsupported coding protocol: " + value);
    }
    static <T> T required(T value, String field) {
        require(value != null, field + " is required"); return value;
    }
    static String text(String value, String field, int max) {
        require(value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max,
            field + " must be nonblank and at most " + max + " Unicode scalar values");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)), field + " has invalid Unicode");
            } else require(!Character.isLowSurrogate(c), field + " has invalid Unicode");
        }
        return value;
    }
    static String ref(String value, String field) { return text(value, field, 512); }
    static String optionalRef(String value, String field) { return value == null ? null : ref(value, field); }
    static long count(long value, String field) {
        require(value >= 0 && value <= MAX_SAFE_INTEGER, field + " must be a nonnegative safe integer"); return value;
    }
    static Instant time(Instant value, String field) {
        required(value, field);
        require(value.getNano() % 1_000_000 == 0 && value.toString().matches("[0-9]{4}-.*Z"),
            field + " must have millisecond precision and a four-digit UTC year");
        return value;
    }
    static String decimal(String value, String field) {
        text(value, field, 64);
        require(value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]*[1-9])?"),
            field + " must be a nonnegative canonical decimal string (no exponent/trailing zeros)");
        return value;
    }
    static String hash(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "SHA-256 must be 64 lowercase hex characters"); return value;
    }
    static KernelDataScope scope(KernelDataScope scope) {
        required(scope, "scope"); text(scope.tenantId(), "tenantId", 128);
        text(scope.userId(), "userId", 128); text(scope.runId(), "runId", 128); return scope;
    }
    static boolean sameScope(KernelDataScope left, KernelDataScope right) {
        return Objects.equals(left.tenantId(), right.tenantId()) && Objects.equals(left.userId(), right.userId())
            && Objects.equals(left.runId(), right.runId());
    }
    static List<String> strings(List<String> values, String field, boolean sorted) {
        required(values, field); require(values.size() <= 256, field + " has too many entries");
        values.forEach(value -> ref(value, field));
        require(values.stream().distinct().count() == values.size(), field + " contains duplicates");
        return sorted ? values.stream().sorted().toList() : List.copyOf(values);
    }
}
