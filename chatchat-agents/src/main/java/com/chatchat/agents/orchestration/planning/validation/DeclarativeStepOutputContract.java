package com.chatchat.agents.orchestration.planning.validation;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Non-executable nodes return input verbatim; instructions cannot satisfy data dependencies. */
public final class DeclarativeStepOutputContract {
    private DeclarativeStepOutputContract() { }
    public static void validate(InterpretationPlan plan, List<String> issues) {
        if (plan == null || plan.plan() == null || plan.plan().edgeContracts() == null) return;
        Map<Integer, InterpretationPlan.Step> steps = plan.steps().stream()
            .filter(s -> s != null && s.id() != null)
            .collect(Collectors.toMap(InterpretationPlan.Step::id, Function.identity(), (a,b) -> a));
        var paths = new ToolProtocolPayloadNavigator();
        for (var edge : plan.plan().edgeContracts()) {
            if (edge == null || Boolean.FALSE.equals(edge.required())) continue;
            var source = steps.get(edge.from());
            var target = steps.get(edge.to());
            if (source == null || source.mcpToolAction() || source.finalAnswerAction()
                    || target == null || target.finalAnswerAction()) continue;
            if (paths.valueAtPath(source.input(), edge.field()) == null) {
                issues.add("Non-executable " + source.actionType() + " step " + source.id()
                    + " cannot produce required field " + edge.field()
                    + "; instruction text is not execution. Bind an actual upstream tool value directly "
                    + "or use an available executable capability; do not invent a derived output.");
            }
        }
    }
}
