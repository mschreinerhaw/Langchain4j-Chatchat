package com.chatchat.api.application.interaction.service;

import com.chatchat.api.application.interaction.model.InteractionContext;
import com.chatchat.api.application.interaction.model.InteractionMode;
import com.chatchat.api.application.interaction.model.InteractionRequest;
import com.chatchat.api.application.interaction.model.InteractionResponse;

/**
 * Strategy contract for mode-specific interaction handling.
 */
public interface InteractionModeHandler {

    InteractionMode mode();

    InteractionResponse handle(InteractionRequest request, InteractionContext context);
}

