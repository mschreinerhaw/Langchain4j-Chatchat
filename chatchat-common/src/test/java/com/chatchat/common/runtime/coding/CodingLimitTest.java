package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingLimitTest {
    @Test void unlimitedIsExplicitAndCountersStillHaveStrictNumericMeaning() {
        assertThat(CodingLimit.parse("unlimited").value()).isEqualTo(-1);
        assertThat(CodingLimit.parse(-1).wire()).isEqualTo("unlimited");
        assertThat(CodingLimit.hasRoom(-1,1000000)).isTrue();
        assertThat(CodingLimit.hasRoom(12,12)).isFalse();
        assertThat(CodingLimit.remaining(-1,12)).isEqualTo(-1);
        for(Object invalid:new Object[]{-2,Double.POSITIVE_INFINITY,"garbage","1.5"})
            assertThatThrownBy(()->CodingLimit.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void budgetsAcceptTheUnlimitedWordAndRoundTripWithoutChangingFiniteCanonicalFields() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var finite=new CodingTaskContract.Budget("budget",Instant.parse("2026-11-10T00:00:00Z"),12,65536,24,12,"10","USD",300000,536870912,4194304);
        assertThat(json.readValue(json.writeValueAsString(finite),CodingTaskContract.Budget.class)).isEqualTo(finite);
        var node=json.valueToTree(finite);
        for(String field:new String[]{"maxModelCalls","maxModelTokens","maxToolCalls","maxCodeExecutions","maxCpuMillis","maxOutputBytes"})
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put(field,"unlimited");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("maxCostAmount","unlimited");
        var unlimited=json.treeToValue(node,CodingTaskContract.Budget.class);
        assertThat(unlimited.maxModelCalls()).isEqualTo(-1);assertThat(unlimited.maxModelTokens()).isEqualTo(-1);
        assertThat(json.readValue(json.writeValueAsString(unlimited),CodingTaskContract.Budget.class)).isEqualTo(unlimited);
        assertThatThrownBy(()->new CodingTaskOutcome.Counters(-1,0,0,0,0,0,0,"0")).isInstanceOf(IllegalArgumentException.class);
    }
}
