package com.chatchat.api.runtime.coding.analysis;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSnapshotStatisticsTest {
    @Test void distinguishesObservedTypesAndNullsWithoutDeducingBusinessMeaning() {
        var stats=new NativeSnapshotStatistics();
        stats.observe("typed",new BigDecimal("0.1"));
        stats.observe("typed",new BigDecimal("0.2"));
        stats.observe("typed",null);
        stats.observe("mixed",12);
        stats.observe("mixed","12");
        stats.observe("number_like","42");
        var fields=stats.snapshot(4);
        var number=(Map<?,?>)fields.get("typed");
        assertEquals(2L,number.get("nonNull"));
        assertEquals(2L,number.get("nullOrMissing"));
        assertEquals(Map.of("min","0.1","max","0.2","sum","0.3"),number.get("numeric"));
        assertFalse(((Map<?,?>)fields.get("mixed")).containsKey("numeric"));
        assertFalse(((Map<?,?>)fields.get("number_like")).containsKey("numeric"));
    }
}
