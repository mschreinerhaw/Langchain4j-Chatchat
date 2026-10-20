package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;

/** Reasoning over supplied context; grants no business data access. */
@FunctionalInterface
public interface CodingAnswerPort {
    InteractionResponse answer(InteractionRequest request, InteractionContext context,
                               SkillDefinition agent, String suppliedContext);
}
