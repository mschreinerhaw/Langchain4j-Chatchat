package com.chatchat.chat.interaction.service.handler;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.interaction.service.CodingAnswerPort;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmChatModeHandlerTest {
    @Test void persistedMetadataRequestsAvoidCodingWhileOrdinaryQuestionsKeepTheirExistingRoute(){
        var coding=mock(CodingAnswerPort.class);var metadata=mock(com.chatchat.chat.interaction.service.SqlMetadataAnswerPort.class);
        var handler=new LlmChatModeHandler(coding);handler.setSqlMetadata(metadata);
        var request=request("chosen");when(metadata.supports(request)).thenReturn(true);
        when(metadata.answer(request,context)).thenReturn(InteractionResponse.builder().answer("Metadata answer").build());
        assertThat(handler.handle(request,context).getAnswer()).isEqualTo("Metadata answer");verifyNoInteractions(coding);
        when(metadata.supports(request)).thenReturn(false);when(coding.answer(any(),any(),isNull(),anyString())).thenReturn(InteractionResponse.builder().answer("Coding answer").build());
        assertThat(handler.handle(request,context).getAnswer()).isEqualTo("Coding answer");
    }
    private final CodingAnswerPort coding = mock(CodingAnswerPort.class);
    private final LlmChatModeHandler handler = new LlmChatModeHandler(coding);
    private final InteractionContext context = InteractionContext.builder().history(List.of()).build();
    private InteractionRequest request(String model) {
        return InteractionRequest.builder().modelName(model).query("Generate SQL")
            .systemPrompt("Return sql-studio.output.v1 JSON").build();
    }
    @Test void engineeringContractAndSelectedModelReachCodingWithoutSelectingAnAgent() {
        when(coding.answer(any(),any(),isNull(),anyString())).thenAnswer(call -> {
            assertThat(call.<InteractionRequest>getArgument(0).getModelName()).isEqualTo("chosen");
            assertThat(call.<InteractionRequest>getArgument(0).getSystemPrompt()).contains("sql-studio.output.v1");
            assertThat(call.<String>getArgument(3)).isEmpty();
            return InteractionResponse.builder().answer("{\"files\":[]}").metadata(Map.of("codingReceipt","receipt")).build();
        });
        var response = handler.handle(request("chosen"),context);
        assertThat(response.getAnswer()).isEqualTo("{\"files\":[]}");
        assertThat(response.getMetadata()).containsEntry("codingReceipt","receipt");
    }
    @Test void blankModelRemainsAnExplicitRequestForTheConfiguredDefault() {
        when(coding.answer(any(),any(),isNull(),anyString())).thenAnswer(call -> {
            assertThat(call.<InteractionRequest>getArgument(0).getModelName()).isEmpty();
            return InteractionResponse.builder().answer("answer").build();
        });
        assertThat(handler.handle(request(""),context).getAnswer()).isEqualTo("answer");
    }
    @Test void cancellationPreventsDeliveryAndRejectsLateResponses() {
        var cancelled = new AtomicBoolean(true);
        var request = request(""); request.setToolInput(Map.of("__agentCancellation",(BooleanSupplier)cancelled::get));
        assertThatThrownBy(() -> handler.handle(request,context)).isInstanceOf(CancellationException.class);
        verifyNoInteractions(coding);
        cancelled.set(false);
        when(coding.answer(any(),any(),isNull(),anyString())).thenAnswer(call -> {
            cancelled.set(true); return InteractionResponse.builder().answer("late").build();
        });
        assertThatThrownBy(() -> handler.handle(request,context)).isInstanceOf(CancellationException.class);
    }
}
