package com.chatchat.api.runtime.coding;

import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingConfigurationSnapshotTest {
    @Test void replayCannotSilentlyBindAChangedModelToTheOriginalTaskSnapshot() {
        var snapshot=CodingConfigurationSnapshot.capture(Map.of("checkpointModelConfig",Map.of("selectedModel","A","timeout",120L)),Map.of());
        assertThatCode(()->CodingConfigurationSnapshot.requireSameModel(snapshot,
            Map.of("checkpointModelConfig",Map.of("selectedModel","A","timeout",120)),Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(()->CodingConfigurationSnapshot.requireSameModel(snapshot,
            Map.of("checkpointModelConfig",Map.of("selectedModel","B")),Map.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void reusesRunIdentityAndValidatedSkillsWithoutExportingConnectionSecrets() {
        var skill = SkillAnalysisContext.create("APPLIED",List.of(Map.of("id","approved","name","Analysis","version","v1","instructions",List.of("FULL_DOCUMENT_SENTINEL"))),
            Map.of("ANALYSIS",List.of("Calculate only measured values")));
        var attributes = new HashMap<String,Object>();
        attributes.put("checkpointModelConfig",Map.of("selectedModel","selected-A","providerModel","actual-A",
            "protocol","openai","maxTokens",8192,"baseUrl","https://private:secret@host","apiKey","secret"));
        attributes.put(SkillAnalysisContext.ATTRIBUTE,skill);
        var snapshot = CodingConfigurationSnapshot.capture(attributes,Map.of("modelName","wrong-default"));
        assertThat(snapshot.get("model").toString()).contains("selected-A","actual-A","8192")
            .doesNotContain("wrong-default","secret","host","apiKey");
        assertThat(snapshot.get("skills")).isEqualTo(SkillAnalysisContext.catalog(skill));
        assertThat(snapshot.get("skills").toString()).doesNotContain("FULL_DOCUMENT_SENTINEL", "Calculate only measured values");
        attributes.clear();
        assertThat(snapshot.get("skills")).isEqualTo(SkillAnalysisContext.catalog(skill));
    }
    @Test void forgedSkillFingerprintIsNotLoadedAndMissingModelIsNotGuessed() {
        var snapshot = CodingConfigurationSnapshot.capture(Map.of(SkillAnalysisContext.ATTRIBUTE,
            Map.of("schemaVersion",SkillAnalysisContext.VERSION,"status","APPLIED","fingerprint","forged")),Map.of());
        assertThat((Map<?,?>)snapshot.get("skills")).isEmpty();
        assertThat(((Map<?,?>)snapshot.get("model")).containsKey("selectedModel")).isFalse();
    }
}
