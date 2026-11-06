package com.chatchat.common.runtime.coding;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingDataStatusTest {
    @Test void zeroRowsOnlyProveAnEmptySliceAndNeverSourceAbsenceOrAFilterCause() {
        var result=CodingDataStatus.describe(List.of(),true,List.of(),Map.of("filters",Map.of("code","missing")));
        assertThat(result).containsEntry("status","EMPTY_RESULT").containsEntry("sourceEmptyProven",false)
            .containsEntry("cause","UNKNOWN").containsEntry("automaticRetryAuthorized",false);
    }
    @Test void aPartialEmptyResponseDoesNotProveSuccessfulEmptyDelivery() {
        assertThat(CodingDataStatus.describe(List.of(),false,List.of(),Map.of()))
            .containsEntry("status","PARTIAL_DATA").containsEntry("sourceEmptyProven",false);
    }
    @Test void dateAndFilterMismatchesRequireActualContradictoryRows() {
        var result=CodingDataStatus.describe(List.of(Map.of("observation_date","2026-11-02","code","301218")),true,
            List.of(),Map.of("start_date","2026-11-03","filters",Map.of("code","other")));
        assertThat(result).containsEntry("status","DATE_MISMATCH");
        assertThat(result.get("conditions")).asList().contains("DATE_MISMATCH","FILTER_MISMATCH");
        assertThat(CodingDataStatus.describe(List.of(Map.of("observation_date","unknown")),true,List.of(),Map.of("trade_date","2026-11-02")))
            .doesNotContainEntry("status","DATE_MISMATCH");
    }
    @Test void absentNullAndBlankFieldsAreReportedWithoutDiscardingOtherValues() {
        var row=new LinkedHashMap<String,Object>();row.put("price",null);row.put("date","");row.put("code","301218");
        var result=CodingDataStatus.describe(List.of(row,Map.of("price",81.44,"date","2026-11-02")),true,List.of("price","date"),Map.of());
        assertThat(result).containsEntry("status","FIELD_MISSING").containsEntry("rowsMissingRequiredFields",1)
            .containsEntry("returnedRowCount",2).containsEntry("missingFields",List.of("date","price"));
        assertThat(row).containsEntry("price",null).containsEntry("code","301218");
    }
}
