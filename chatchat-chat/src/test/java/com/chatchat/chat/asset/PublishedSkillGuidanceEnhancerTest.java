package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.interaction.service.CodingAnswerPort;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.asset.AssetContext;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedSkillGuidanceEnhancerTest {
    final CodingAnswerPort coding = mock(CodingAnswerPort.class);
    final SkillCatalogService agents = mock(SkillCatalogService.class);
    final SkillDefinition agent = mock(SkillDefinition.class);
    final AnalysisContext context = new AnalysisContext("Explain this resource",new KernelDataScope("t","u","request","session","run",null,Map.of()),
        "agent",List.of(),List.of(),List.of("role"),null,Map.of());
    final AssetContext asset = new AssetContext("resource","API","Resource","Published description",Map.of(),Map.of(),Map.of(),"discovery",Map.of());
    final PublishedSkillGuidanceEnhancer enhancer = new PublishedSkillGuidanceEnhancer(coding,agents,new ObjectMapper());
    PublishedSkillGuidanceEnhancerTest() { when(agents.resolve("agent")).thenReturn(agent); }
    @Test void authorizedMetadataAndSkillsReachCodingUnderTheOriginalIdentity() {
        var snapshot = SkillAnalysisContext.create("DELIVERED",List.of(Map.of("id","skill","instructions",List.of("original"))),Map.of());
        var supplied = context.withAttribute(SkillAnalysisContext.ATTRIBUTE,snapshot);
        when(coding.answer(any(),any(),eq(agent),anyString())).thenAnswer(call -> {
            InteractionRequest request = call.getArgument(0);
            InteractionContext conversation = call.getArgument(1);
            assertThat(request.getTenantId()).isEqualTo("t"); assertThat(request.getUserId()).isEqualTo("u");
            assertThat(request.getToolInput()).containsEntry("__agentRunId","run");
            assertThat(conversation.skillAnalysisContext()).isEqualTo(snapshot);
            assertThat(call.<String>getArgument(3)).contains("Authorized asset metadata only","Published description","Missing output contracts");
            return InteractionResponse.builder().answer("Coding's bounded recommendation").build();
        });
        var result = enhancer.recommend(supplied,asset);
        assertThat(result.status()).isEqualTo("CODING_REVIEWED");
        assertThat(result.advice()).isEqualTo("Coding's bounded recommendation");
        assertThat(result.skillIds()).isEmpty(); // Candidate delivery is not proof of activation.
    }
    @Test void metadataWithoutSkillsStillHasOneCodingOwner() {
        when(coding.answer(any(),any(),any(),anyString())).thenReturn(InteractionResponse.builder().answer("Declared metadata only").build());
        assertThat(enhancer.recommend(context,asset).status()).isEqualTo("CODING_REVIEWED");
        verify(coding,times(1)).answer(any(),any(),any(),anyString());
    }
    @Test void workerFailurePreservesMetadataAvailabilityWithoutAJavaModelFallback() {
        when(coding.answer(any(),any(),any(),anyString())).thenThrow(new IllegalStateException("worker failed"));
        assertThat(enhancer.recommend(context,asset).status()).isEqualTo("UNAVAILABLE");
        verify(coding,times(1)).answer(any(),any(),any(),anyString());
    }
    @Test void cancellationIsNeverConvertedIntoUnavailableAdvice() {
        var cancelled = context.withAttribute("__agentCancellation",(java.util.function.BooleanSupplier)() -> true);
        assertThatThrownBy(() -> enhancer.recommend(cancelled,asset)).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(coding);
    }
}
