package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.BoundCodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingContractException;
import com.chatchat.common.runtime.coding.CodingErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Carries an identity established by a trusted Runtime entry point, never by a task payload.
 * Callback and user entry points must bind their authenticated actor for each invocation.
 */
@Component
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class CodingInvocationContext implements CodingTaskAccess {
    private record Actor(KernelDataScope scope, Set<Action> grants) { }
    private final ThreadLocal<Actor> current = new ThreadLocal<>();

    public <T> T as(KernelDataScope identity, Set<Action> grants, Supplier<T> operation) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(grants);
        Objects.requireNonNull(operation);
        if (identity.tenantId() == null || identity.userId() == null || identity.runId() == null
                || grants.isEmpty() || grants.stream().anyMatch(Objects::isNull))
            throw denied();
        Actor previous = current.get();
        current.set(new Actor(identity, Set.copyOf(grants)));
        try { return operation.get(); }
        finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    @Override public void require(KernelDataScope scope, Action action) {
        Actor actor = current.get();
        if (actor == null || scope == null || !actor.grants().contains(action)
                || !Objects.equals(actor.scope().tenantId(), scope.tenantId())
                || !Objects.equals(actor.scope().userId(), scope.userId())
                || !Objects.equals(actor.scope().runId(), scope.runId()))
            throw denied();
    }

    @Override public CodingTaskAccess subscriptionAccess(KernelDataScope scope) {
        require(scope, Action.READ);
        // Capture only a previously authenticated run and READ; no worker or submission grant escapes.
        return new BoundCodingTaskAccess(() -> scope, Set.of(Action.READ));
    }

    private static CodingContractException denied() {
        return new CodingContractException(CodingErrorCode.AUTHORIZATION_DENIED,
            "Coding task action is not bound to an authenticated Runtime actor");
    }
}
