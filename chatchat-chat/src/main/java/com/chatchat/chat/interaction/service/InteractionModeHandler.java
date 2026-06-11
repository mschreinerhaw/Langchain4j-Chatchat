package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;

/**
 * Strategy contract for mode-specific interaction handling.
 */
public interface InteractionModeHandler {

    /**
     * Performs the mode operation.
     *
     * @return the operation result
     */
    InteractionMode mode();

    /**
     * Handles the handle.
     *
     * @param request the request value
     * @param context the context value
     * @return the operation result
     */
    InteractionResponse handle(InteractionRequest request, InteractionContext context);
}

