package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingExecutionProfileTest {
    @Test void selectionUsesOnlyDataVolumeAndTrustedRegisteredAliasConfiguration() {
        var policy = new CodingAnalysisProperties();
        policy.setSimpleModelName("registered-fast");
        policy.setComplexModelName("registered-large");
        policy.setSimpleReasoningEffort("low");
        assertThat(CodingExecutionProfile.select(policy, 1, 100))
            .containsEntry("complexity", "SIMPLE").containsEntry("selectedModel", "registered-fast")
            .containsEntry("reasoningEffort", "low");
        assertThat(CodingExecutionProfile.select(policy, 8, 100))
            .containsEntry("complexity", "COMPLEX").containsEntry("selectedModel", "registered-large")
            .doesNotContainKey("reasoningEffort");
        var frozen=new java.util.LinkedHashMap<String,Object>(CodingExecutionProfile.select(policy,1,100));
        frozen.put("providerModel","provider-fast");frozen.put("protocol","openai");
        frozen.put("implementation","test.model");
        var context=Map.<String,Object>of("schemaVersion","runtime_os.coding.context.v1",
            "consistency","TASK_SNAPSHOT","executionProfile",frozen);
        assertThat(CodingRuntimeContext.validate(context)).containsKey("executionProfile");
    }

    @Test void invalidUntrustedOrProviderReasoningSettingsAreRejectedBeforeDispatch() {
        var policy = new CodingAnalysisProperties();
        policy.setSimpleModelName("../../unregistered");
        assertThatThrownBy(() -> CodingExecutionProfile.select(policy,1,1))
            .isInstanceOf(IllegalArgumentException.class);
        policy.setSimpleModelName(null);
        policy.setSimpleReasoningEffort("vendor_unknown");
        assertThatThrownBy(() -> CodingExecutionProfile.select(policy,1,1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
