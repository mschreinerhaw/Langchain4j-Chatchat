package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.api.runtime.coding.nativecline.NativeS3Settings;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.runtime.coding.CodingReasoning;
import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingReasoningPolicyTest {
    @Test void authorizedCapabilitiesLimitExplicitAndAutomaticEffortAndFreezeTheSelection() throws Exception {
        var settings = new CodingAnalysisProperties();
        settings.setReasoningEffortsByModel(Map.of("registered",List.of("low","high","bogus")));
        settings.setSimpleReasoningEffort("low");
        settings.setComplexReasoningEffort("high");
        var registry = mock(ModelResourceRegistry.class);
        when(registry.selectableChatModels()).thenReturn(List.of("registered"));
        var connection = new ModelsConfig.ModelConnectionConfig();
        connection.setBaseUrl("https://provider.example/v1");
        when(registry.connection("registered")).thenReturn(connection);
        @SuppressWarnings("unchecked")
        ObjectProvider<NativeS3Settings> nativeSettings = mock(ObjectProvider.class);
        var policy = new CodingReasoningPolicy(settings,registry,nativeSettings);
        assertThat(policy.options("registered")).containsExactly("low","high");
        assertThat(policy.options("hidden")).isEmpty();
        assertThat(policy.freeze("high","hidden",1,1)).containsExactlyEntriesOf(Map.of("requested","high"));
        assertThat(policy.freeze("xhigh","registered",1,1)).containsExactlyEntriesOf(Map.of("requested","xhigh"));
        var off=policy.freeze("off","registered",1,1);
        assertThat(off).containsExactlyEntriesOf(Map.of("requested","off"));
        CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","reasoning",off));
        assertThat(policy.freeze(null,"registered",1,1)).isEmpty();
        assertThat(policy.freeze("auto","registered",1,1)).containsEntry("effective","low");
        var frozen = policy.freeze("auto","registered",3,1);
        var mapper = new ObjectMapper();
        var serialized = mapper.writeValueAsBytes(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","reasoning",frozen));
        Map<String,Object> restored = mapper.readValue(serialized,new TypeReference<>(){});
        CodingRuntimeContext.validate(restored);
        settings.setComplexReasoningEffort("low");
        assertThat(CodingReasoning.requestedFromContext(restored)).isEqualTo("auto");
        assertThat(CodingReasoning.effective(restored)).isEqualTo("high");
        assertThat(policy.compatibleEffort("registered",CodingReasoning.effective(restored),null)).isEqualTo("high");
        connection.setProtocol("dashscope-native");
        assertThat(policy.options("registered")).isEmpty();
        assertThat(policy.freeze("auto","registered",1,1)).containsOnlyKeys("requested");
        assertThat(policy.compatibleEffort("registered","high",null)).isNull();
    }

    @Test void publishedMappingsDefaultToMediumAndFreezeAcrossPublicationEdits() {
        var settings=new CodingAnalysisProperties();
        settings.setReasoningEffortsByModel(Map.of("model",List.of("low")));
        var registry=mock(ModelResourceRegistry.class);
        when(registry.selectableChatModels()).thenReturn(List.of("model"));
        var connection=new ModelsConfig.ModelConnectionConfig();
        connection.setBaseUrl("https://provider.example/v1");
        when(registry.connection("model")).thenReturn(connection);
        @SuppressWarnings("unchecked")
        ObjectProvider<NativeS3Settings> nativeSettings=mock(ObjectProvider.class);
        var policy=new CodingReasoningPolicy(settings,registry,nativeSettings);
        var catalog=mock(com.chatchat.api.model.PlatformModelCatalogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(policy,"catalog",catalog);
        when(catalog.publishedReasoning("model")).thenReturn(new com.chatchat.api.model.ModelReasoningConfig(
            true,"deepseek-thinking",Map.of("low","low","medium","high","xhigh","max")));
        var context=Map.<String,Object>of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT",
            "reasoning",policy.freeze(null,"model",1,0));
        CodingRuntimeContext.validate(context);
        assertThat(policy.options("model")).containsExactly("low","medium","xhigh");
        assertThat(CodingReasoning.requestedFromContext(context)).isEqualTo("medium");
        assertThat(CodingReasoning.submittedFromContext(context)).isNull();
        assertThat(CodingReasoning.effective(context)).isEqualTo("high");
        when(catalog.publishedReasoning("model")).thenReturn(new com.chatchat.api.model.ModelReasoningConfig(false,null,null));
        assertThat(policy.options("model")).isEmpty();
        assertThat(policy.freeze(null,"model",1,0)).isEqualTo(Map.of("requested","off","source","model-default"));
        assertThat(policy.compatibleEffort("model",CodingReasoning.effective(context),CodingReasoning.parameter(context))).isNull();
        assertThat(policy.freeze("medium","model",1,0)).containsExactlyEntriesOf(Map.of("requested","medium"));
    }

    @Test void invalidPreferenceAndForgedExplicitSnapshotFailClosed() {
        assertThatThrownBy(() -> CodingReasoning.requested(Map.of(CodingReasoning.REQUEST_KEY,"highest")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1",
            "reasoning",Map.of("requested","low","effective","high")))).isInstanceOf(IllegalArgumentException.class);
    }
}
