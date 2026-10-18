package com.chatchat.agents.orchestration.planning.validation;

import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolExecutionTimeScopeTest {
    final AgentRuntimeGuard guard=new AgentRuntimeGuard(3,"cancel","steps","calls","timeout","__agentDeadlineAt");

    @Test void waitBeyondCallerTimeLeavesCallerTimeAvailableThroughCopiedAttributes() throws Exception {
        var attrs=guard.attributesWithDeadline(Map.of("timeout",100));
        var copy=new LinkedHashMap<>(attrs);
        var deadline=(AtomicLong)attrs.get("__agentDeadlineAt");
        long original=deadline.get();
        try(var wait=ToolExecutionTimeScope.open(copy,System.currentTimeMillis()+5_000)) {
            Thread.sleep(200);
            assertThat(guard.cancellationCheck(attrs).getAsBoolean()).isFalse();
        }
        assertThat(deadline.get()).isGreaterThanOrEqualTo(original+195);
        assertThat(guard.remainingTimeMs(attrs)).isPositive().isLessThanOrEqualTo(100);
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(attrs))
            .path("__agentDeadlineAt").isNumber()).isTrue();
    }
    @Test void failureRefundsUnusedReservationAndExplicitCancellationStillWorks() {
        var cancelled=new AtomicBoolean();
        var attrs=guard.attributesWithDeadline(Map.of("timeout",10_000,"cancel",(java.util.function.BooleanSupplier)cancelled::get));
        long original=((Number)attrs.get("__agentDeadlineAt")).longValue();
        assertThatThrownBy(()->{
            try(var wait=ToolExecutionTimeScope.open(attrs,System.currentTimeMillis()+60_000)) {
                cancelled.set(true);
                guard.checkCancelled(guard.cancellationCheck(attrs));
            }
        }).isInstanceOf(CancellationException.class);
        assertThat(((Number)attrs.get("__agentDeadlineAt")).longValue()).isBetween(original,original+5_000);
    }
    @Test void expiredToolCannotAddMoreTimeAndScopeCloseIsIdempotent() {
        var attrs=guard.attributesWithDeadline(Map.of("__agentDeadlineAt",1));
        var scope=ToolExecutionTimeScope.open(attrs,1);scope.close();scope.close();
        assertThat(guard.remainingTimeMs(attrs)).isZero();
        assertThatThrownBy(()->guard.cancellationCheck(attrs).getAsBoolean()).isInstanceOf(AgentDeadlineExceededException.class);
    }
}
