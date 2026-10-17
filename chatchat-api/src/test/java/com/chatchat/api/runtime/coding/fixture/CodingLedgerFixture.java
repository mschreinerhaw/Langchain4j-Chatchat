package com.chatchat.api.runtime.coding.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared mutable fixture rows for verifier and Sandbox integration tests. */
public final class CodingLedgerFixture {
    private CodingLedgerFixture() { }

    public static Map<String, Object> validationRules() {
        return Map.of("tradingDates", List.of("2026-10-12", "2026-10-13", "2026-10-14"),
            "openingAssets", "100.00", "moneyScale", 2, "returnScale", 8, "roundingMode", "HALF_EVEN");
    }

    public static List<Map<String, Object>> cleanRows() {
        return new ArrayList<>(List.of(
            new LinkedHashMap<>(Map.of("date", "2026-10-12", "assets", "110.00", "netFlow", "0.00", "returnRate", "0.10000000")),
            new LinkedHashMap<>(Map.of("date", "2026-10-13", "assets", "121.00", "netFlow", "0.00", "returnRate", "0.10000000")),
            new LinkedHashMap<>(Map.of("date", "2026-10-14", "assets", "133.10", "netFlow", "0.00", "returnRate", "0.10000000"))));
    }
}
