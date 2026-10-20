package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectAnswerWorkflowTest {
    @Test void contextAndSkillsReachCodingWithoutJavaModelFallback() {
        var coding = mock(CodingAnswerPort.class);
        var request = InteractionRequest.builder().query("Design a generic schema").build();
        var context = InteractionContext.builder().skillAnalysisContext(java.util.Map.of("marker", "test")).build();
        var skill = mock(SkillDefinition.class);
        var response = InteractionResponse.builder().answer("Coding proposal").build();
        when(coding.answer(request, context, skill, "")).thenReturn(response);
        assertThat(new DirectAnswerWorkflow(coding).execute(request, context, skill)).isSameAs(response);
        verify(coding).answer(request, context, skill, "");
    }
    @Test void cancellationPreventsDelegation() {
        var coding = mock(CodingAnswerPort.class);
        var request = InteractionRequest.builder().toolInput(java.util.Map.of(
            "__agentCancellation", (java.util.function.BooleanSupplier) () -> true)).build();
        assertThatThrownBy(() -> new DirectAnswerWorkflow(coding).execute(request,
            InteractionContext.builder().build(), mock(SkillDefinition.class)))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(coding);
    }
    @Test void lateCodingResultCannotOverrideCancellation() {
        var coding = mock(CodingAnswerPort.class);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(coding.answer(any(), any(), any(), anyString())).thenAnswer(call -> {
            cancelled.set(true); return InteractionResponse.builder().answer("late").build();
        });
        var request = InteractionRequest.builder().toolInput(java.util.Map.of(
            "__agentCancellation", (java.util.function.BooleanSupplier) cancelled::get)).build();
        assertThatThrownBy(() -> new DirectAnswerWorkflow(coding).execute(request,
            InteractionContext.builder().build(), mock(SkillDefinition.class)))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
    @Test void failedCodingNeverStartsAnotherAnswerOwner() {
        var coding = mock(CodingAnswerPort.class);
        when(coding.answer(any(), any(), any(), anyString())).thenThrow(new IllegalStateException("worker down"));
        assertThatThrownBy(() -> new DirectAnswerWorkflow(coding).execute(InteractionRequest.builder().build(),
            InteractionContext.builder().build(), mock(SkillDefinition.class))).hasMessage("worker down");
        verify(coding, times(1)).answer(any(), any(), any(), anyString());
    }
}
