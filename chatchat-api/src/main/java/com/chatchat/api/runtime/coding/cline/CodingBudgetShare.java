package com.chatchat.api.runtime.coding.cline;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Admission shares must fit the cumulative cap even when unknown usage stays reserved. */
final class CodingBudgetShare {
    private CodingBudgetShare() { }

    static long units(long total, long slots) {
        if (total < 0 || slots < 1) throw new IllegalArgumentException("Invalid budget share");
        return total == 0 ? 0 : Math.max(1, total / slots);
    }

    static String amount(String total, long slots) {
        if (slots < 1) throw new IllegalArgumentException("Invalid budget share");
        var cap = new BigDecimal(total);
        if (cap.signum() < 0) throw new IllegalArgumentException("Negative budget");
        var share = cap.divide(BigDecimal.valueOf(slots), 12, RoundingMode.DOWN);
        // A positive tiny cap must not produce free, unmetered admissions.
        if (cap.signum() > 0 && share.signum() == 0) share = cap;
        return share.stripTrailingZeros().toPlainString();
    }
}
