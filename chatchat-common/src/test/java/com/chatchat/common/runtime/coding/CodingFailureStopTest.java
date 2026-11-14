package com.chatchat.common.runtime.coding;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingFailureStopTest {
    @Test void executionFailureCanEnterStopReconciliationWithoutBeingRejectedByItsOwnOutcome(){
        var control=new CodingTaskOutcome.ExecutionControl(CodingErrorCode.EXECUTION_FAILED,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),false,true);
        assertThat(control.stopReason()).isEqualTo(CodingErrorCode.EXECUTION_FAILED);
        assertThat(control.reconciliationRequired()).isTrue();
        assertThat(control.executorStopped()).isFalse();
    }
    @Test void unrelatedErrorsCannotBecomeStopFences(){
        assertThatThrownBy(()->new CodingTaskOutcome.ExecutionControl(CodingErrorCode.AUTHORIZATION_DENIED,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),false,true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

