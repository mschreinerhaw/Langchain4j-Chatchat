package com.chatchat.agents.runtime.coding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingCompletionBudgetTest {
    @Test void unlimitedCountsDoNotBecomeZeroExplorationOrForceCompletion() {
        var policy=new CodingCompletionBudget(.15,.35);
        assertThat(policy.allocate(-1,-1,100000,-1,-1,90000,500,6,40,false))
            .containsEntry("phase","EXPLORATION").containsEntry("explorationTokens","unlimited")
            .containsEntry("explorationCalls","unlimited").containsEntry("reservedCalls",0L);
        assertThat(policy.allocate(-1,-1,100000,-1,-1,1,500,6,40,false))
            .containsEntry("phase","COMPLETION");
    }
    @Test void reservationScalesWithTheActualContractAndRepairObligations() {
        var policy=new CodingCompletionBudget(.15,.35);
        var simple=policy.allocate(10000,20,100000,9000,19,90000,500,1,0,false);
        var complex=policy.allocate(10000,20,100000,9000,19,90000,500,6,3,false);
        assertThat((Long)complex.get("reservedTokens")).isGreaterThan((Long)simple.get("reservedTokens"));
        assertThat(policy.allocate(10000,20,100000,1500,2,10000,500,6,3,false)).containsEntry("phase","COMPLETION");
        assertThat(policy.allocate(100,1,1000,100,1,1000,4000,6,3,false)).containsEntry("reservedTokens",100L);
        assertThat(simple).containsEntry("phase","EXPLORATION");
        assertThatThrownBy(()->new CodingCompletionBudget(.4,.2)).isInstanceOf(IllegalArgumentException.class);
    }
}
