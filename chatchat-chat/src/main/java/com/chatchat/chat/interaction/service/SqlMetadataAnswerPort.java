package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.*;

/** SQL Studio's persisted metadata-only requests; implementations must verify server-owned scope. */
public interface SqlMetadataAnswerPort {
    boolean supports(InteractionRequest request);
    InteractionResponse answer(InteractionRequest request, InteractionContext context);
}
