package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;

/**
 * Strategy contract for mode-specific interaction handling.
 */
public interface InteractionModeHandler {

    InteractionMode mode();

    InteractionResponse handle(InteractionRequest request, InteractionContext context);
}

