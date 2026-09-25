package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Fail-closed dispatch for the five compute families; unavailable nodes are never simulated. */
@Component
public class ComputeNodeRouter {
    private final Map<ComputeNodeType, ExecutionUnit<?, ?>> units;

    public ComputeNodeRouter(List<ExecutionUnit<?, ?>> available) {
        Map<ComputeNodeType, ExecutionUnit<?, ?>> indexed = new EnumMap<>(ComputeNodeType.class);
        available.forEach(unit -> {
            if (indexed.putIfAbsent(unit.nodeType(), unit) != null)
                throw new IllegalStateException("Duplicate execution unit for " + unit.nodeType());
        });
        this.units = Map.copyOf(indexed);
    }

    public boolean available(ComputeNodeType type) { return units.containsKey(type); }

    public <I, O> O execute(ComputeNodeType type, I input, Class<O> outputType, KernelDataScope scope) {
        if (type == null || input == null || outputType == null || scope == null)
            throw new IllegalArgumentException("Compute dispatch requires type, input, output, and scope");
        ExecutionUnit<?, ?> unit = units.get(type);
        if (unit == null) throw new IllegalStateException("Compute node is not registered: " + type);
        if (!unit.inputType().isInstance(input) || !outputType.isAssignableFrom(unit.outputType()))
            throw new IllegalArgumentException("Compute node contract mismatch: " + type);
        return outputType.cast(executeTyped(unit, input, scope));
    }

    private <I, O> O executeTyped(ExecutionUnit<I, O> unit, Object input, KernelDataScope scope) {
        return unit.execute(unit.inputType().cast(input), scope);
    }
}
