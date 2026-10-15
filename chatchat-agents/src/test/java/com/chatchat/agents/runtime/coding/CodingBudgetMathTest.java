package com.chatchat.agents.runtime.coding;

import com.chatchat.common.runtime.coding.CodingTaskContract.Budget;
import com.chatchat.common.runtime.coding.CodingTaskOutcome.Counters;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class CodingBudgetMathTest {
    final Budget limit=new Budget("budget",Instant.parse("2026-10-15T01:00:00Z"),1,2,3,4,"0.3","USD",5,6,7);
    @TestFactory Stream<DynamicTest> eachCumulativeLimitRejectsItsOwnOverrun() {
        return Stream.of(
            new Counters(2,0,0,0,0,0,0,"0"), new Counters(0,3,0,0,0,0,0,"0"),
            new Counters(0,0,4,0,0,0,0,"0"), new Counters(0,0,0,5,0,0,0,"0"),
            new Counters(0,0,0,0,6,0,0,"0"), new Counters(0,0,0,0,0,0,8,"0"),
            new Counters(0,0,0,0,0,0,0,"0.300000000000000001")
        ).map(usage->DynamicTest.dynamicTest(usage.toString(),()->assertThat(CodingBudgetMath.within(usage,limit)).isFalse()));
    }
    @Test void decimalAdditionAndUnknownResidualAreExact() {
        var a=new Counters(1,2,3,4,5,6,7,"0.1");var b=new Counters(1,2,3,4,5,7,7,"0.2");
        var sum=CodingBudgetMath.add(a,b);
        assertThat(sum.costAmount()).isEqualTo("0.3"); assertThat(sum.peakMemoryBytes()).isEqualTo(7);
        assertThat(CodingBudgetMath.remaining(sum,a).costAmount()).isEqualTo("0.2");
        assertThat(CodingBudgetMath.remaining(a,sum)).isEqualTo(CodingBudgetMath.zero());
        assertThat(CodingBudgetMath.covers(a,b)).isFalse();
    }
    @Test void memoryPeakIsNotAnAdmissionBudgetForCumulativeAccounting() {
        assertThat(CodingBudgetMath.within(new Counters(1,2,3,4,5,999,7,"0.3"),limit)).isTrue();
    }
}
