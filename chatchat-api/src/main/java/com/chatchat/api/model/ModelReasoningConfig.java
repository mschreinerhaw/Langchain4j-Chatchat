package com.chatchat.api.model;

import com.chatchat.common.runtime.coding.CodingReasoning;
import java.util.Map;

/** Validated per-model reasoning draft and publication. Null represents a legacy model. */
public record ModelReasoningConfig(boolean enabled, String parameter, Map<String,String> levels) {
    public ModelReasoningConfig {
        if (!enabled) {
            parameter = null;
            levels = Map.of();
        } else {
            if (parameter == null) parameter = "standard";
            if (!CodingReasoning.PARAMETERS.contains(parameter))
                throw new IllegalArgumentException("Unknown reasoning parameter format");
            if (levels == null) levels = Map.of("low","low","medium","medium","xhigh","xhigh");
            if (levels.isEmpty() || !CodingReasoning.LEVELS.containsAll(levels.keySet()))
                throw new IllegalArgumentException("Select at least one valid reasoning level");
            for (String value : levels.values()) {
                if (value == null || !CodingReasoning.permits(parameter,value))
                    throw new IllegalArgumentException("Provider reasoning value is incompatible with the selected format");
            }
            levels = Map.copyOf(levels);
        }
    }
}
