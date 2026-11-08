package com.chatchat.api.runtime.coding.analysis;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CodingCsvMetricObjectiveTest {
    @Test void preservesTheUserRequirementAndPreciseDataBindingsWithoutGenericFinancialOrTransportWork(){
        String original="Reproduce the existing NameError, repair the source, then count rows and sum value. Keep report.md.";
        var objective=CodingCsvMetricObjective.objective(original,"value");
        var prompt=CodingCsvMetricObjective.appendix(objective,List.of("user-asset:exact:sha256"));
        assertThat(prompt).startsWith(original).contains("exact decimal sum of column value","user-asset:exact:sha256","verifiedSummary","Runtime independently recomputes").hasSizeLessThan(3000);
        assertThat(prompt.indexOf(original)).isEqualTo(prompt.lastIndexOf(original));
        assertThat(prompt).doesNotContain("map storage columns","tradingDates","portfolio returns");
    }
}
