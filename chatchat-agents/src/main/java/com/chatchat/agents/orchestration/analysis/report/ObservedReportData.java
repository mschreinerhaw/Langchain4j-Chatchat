package com.chatchat.agents.orchestration.analysis.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact numeric observations copied from Runtime records. No inferred units or computation. */
public record ObservedReportData(String label, BigDecimal value, String recordRef) {
    public static List<ObservedReportData> capture(String reference, List<Map<String, Object>> records) {
        List<ObservedReportData> values = new ArrayList<>();
        for (int index = 0; index < records.size() && values.size() < 60; index++) {
            Map<String, Object> record = records.get(index);
            if (!(record.get("sourcePath") instanceof String path)
                || !(record.get("values") instanceof Map<?, ?> fields)) continue;
            for (var field : fields.entrySet()) {
                if (values.size() >= 60) break;
                if (field.getValue() instanceof Number number) {
                    try {
                        values.add(new ObservedReportData(path + "." + field.getKey(),
                            new BigDecimal(number.toString()), reference + ".records[" + (index + 1) + "]"));
                    } catch (NumberFormatException nonFinite) {
                        // NaN and infinity cannot serve as chart/KPI values.
                    }
                }
            }
        }
        return List.copyOf(values);
    }
}
