package com.chatchat.agents.runtime.federation;

import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import com.chatchat.common.runtime.capability.ModelPromptRequest;
import org.springframework.stereotype.Component;

/** Invokes only configured models through the existing capacity-governed resolver. */
@Component
public class ModelExecutionUnit implements ExecutionUnit<ModelPromptRequest, String> {
    private final AgentChatModelResolver models;

    public ModelExecutionUnit(AgentChatModelResolver models) { this.models = models; }

    @Override public ComputeNodeType nodeType() { return ComputeNodeType.MODEL; }
    @Override public Class<ModelPromptRequest> inputType() { return ModelPromptRequest.class; }
    @Override public Class<String> outputType() { return String.class; }

    @Override public String execute(ModelPromptRequest input, KernelDataScope scope) {
        if (input == null || scope == null || !scope.equals(input.scope()))
            throw new IllegalArgumentException("Model execution scope mismatch");
        return models.resolveChatModel(input.modelName()).chat(input.prompt());
    }
}
