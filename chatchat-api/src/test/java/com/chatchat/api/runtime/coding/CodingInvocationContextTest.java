package com.chatchat.api.runtime.coding;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingContractException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CodingInvocationContextTest {
    private final CodingInvocationContext context = new CodingInvocationContext();
    private final KernelDataScope owner = scope("tenant", "owner", "run");

    @Test void deniesUnboundAndCrossOwnerActionsAndClearsIdentity() {
        assertThatThrownBy(() -> context.require(owner, CodingTaskAccess.Action.READ))
            .isInstanceOf(CodingContractException.class);
        context.as(owner, Set.of(CodingTaskAccess.Action.READ), () -> {
            context.require(owner, CodingTaskAccess.Action.READ);
            assertThatThrownBy(() -> context.require(owner, CodingTaskAccess.Action.WORKER))
                .isInstanceOf(CodingContractException.class);
            assertThatThrownBy(() -> context.require(scope("other", "owner", "run"), CodingTaskAccess.Action.READ))
                .isInstanceOf(CodingContractException.class);
            assertThatThrownBy(() -> context.require(scope("tenant", "other", "run"), CodingTaskAccess.Action.READ))
                .isInstanceOf(CodingContractException.class);
            return null;
        });
        assertThatThrownBy(() -> context.require(owner, CodingTaskAccess.Action.READ))
            .isInstanceOf(CodingContractException.class);
    }

    @Test void restoresOuterIdentityEvenWhenNestedCallFails() {
        context.as(owner, Set.of(CodingTaskAccess.Action.READ), () -> {
            assertThatThrownBy(() -> context.as(scope("other", "owner", "run"),
                Set.of(CodingTaskAccess.Action.WORKER), () -> { throw new IllegalStateException("failure"); }))
                .isInstanceOf(IllegalStateException.class);
            context.require(owner, CodingTaskAccess.Action.READ);
            return null;
        });
    }

    private static KernelDataScope scope(String tenant, String user, String run) {
        return new KernelDataScope(tenant, user, null, null, run, null, Map.of());
    }
}
