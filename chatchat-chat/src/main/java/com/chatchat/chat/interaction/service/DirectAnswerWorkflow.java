package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import org.springframework.stereotype.Component;

/** Tool-free tasks use Coding too. No Java model fallback. */
@Component
public class DirectAnswerWorkflow {
    private final CodingAnswerPort coding;
    public DirectAnswerWorkflow(CodingAnswerPort coding) { this.coding = coding; }
    public InteractionResponse execute(InteractionRequest request, InteractionContext context, SkillDefinition agent) {
        InteractionExecution.checkCancellation(request);
        var response = coding.answer(request, context, agent, "");
        InteractionExecution.checkCancellation(request);
        return response;
    }
}
