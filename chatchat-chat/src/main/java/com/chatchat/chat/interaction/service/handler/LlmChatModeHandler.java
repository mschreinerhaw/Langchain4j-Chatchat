package com.chatchat.chat.interaction.service.handler;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.interaction.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Plain model sessions delegate reasoning to Coding without selecting a business Agent. */
@Component
@RequiredArgsConstructor
public class LlmChatModeHandler implements InteractionModeHandler {
    private final CodingAnswerPort coding;
    @Override public InteractionMode mode() { return InteractionMode.LLM_CHAT; }
    @Override public InteractionResponse handle(InteractionRequest request,InteractionContext context) {
        InteractionExecution.checkCancellation(request);
        var response = coding.answer(request,context,null,"");
        InteractionExecution.checkCancellation(request);
        var metadata = new LinkedHashMap<String,Object>(response.getMetadata() == null ? Map.of() : response.getMetadata());
        metadata.put("handler","LlmChatModeHandler");
        metadata.put("historyUsed",context.history() == null ? 0 : context.history().size());
        metadata.put("summaryUsed",context.conversationSummary() != null && !context.conversationSummary().isBlank());
        metadata.put("conversationEvidenceUsed",context.conversationEvidence() != null && !context.conversationEvidence().isBlank());
        response.setMetadata(metadata);
        return response;
    }
}
