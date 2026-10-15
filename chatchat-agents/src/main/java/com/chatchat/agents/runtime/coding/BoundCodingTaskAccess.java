package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Binds a trusted identity supplier to one Runtime run; never consults payload attributes. */
public final class BoundCodingTaskAccess implements CodingTaskAccess {
    private final Supplier<KernelDataScope> identity;
    private final Set<Action> grants;
    public BoundCodingTaskAccess(Supplier<KernelDataScope> identity, Set<Action> grants) {
        this.identity = Objects.requireNonNull(identity); this.grants = Set.copyOf(grants);
    }
    @Override public void require(KernelDataScope scope, Action action) {
        KernelDataScope actor = identity.get();
        if (actor == null || scope == null || actor.tenantId() == null || actor.userId() == null || actor.runId() == null
            || !Objects.equals(actor.tenantId(),scope.tenantId()) || !Objects.equals(actor.userId(),scope.userId())
            || !Objects.equals(actor.runId(),scope.runId()) || !grants.contains(action))
            throw new CodingContractException(CodingErrorCode.AUTHORIZATION_DENIED, "Coding task scope or action is not authorized");
    }
}
