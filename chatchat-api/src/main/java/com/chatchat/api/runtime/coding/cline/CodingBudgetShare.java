package com.chatchat.api.runtime.coding.cline;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Admission shares must fit the cumulative cap even when unknown usage stays reserved. */
final class CodingBudgetShare {
    private CodingBudgetShare() { }

    static long units(long total, long slots) {
        if (total == -1) return 0; // No aggregate reservation; Sandbox per-process limits remain in force.
        if (slots == -1) return total;
        if (total < 0 || slots < 1) throw new IllegalArgumentException("Invalid budget share");
        return total == 0 ? 0 : Math.max(1, total / slots);
    }

    static String amount(String total, long slots) {
        if ("unlimited".equals(total)) return "0"; // No cost ceiling; usage stays explicitly unsettled when unavailable.
        if (slots == -1) slots=1; // Finite money protection may fail closed on unpriced usage; never divide by an invented call count.
        if (slots < 1) throw new IllegalArgumentException("Invalid budget share");
        var cap = new BigDecimal(total);
        if (cap.signum() < 0) throw new IllegalArgumentException("Negative budget");
        var share = cap.divide(BigDecimal.valueOf(slots), 12, RoundingMode.DOWN);
        // A positive tiny cap must not produce free, unmetered admissions.
        if (cap.signum() > 0 && share.signum() == 0) share = cap;
        return share.stripTrailingZeros().toPlainString();
    }
}
