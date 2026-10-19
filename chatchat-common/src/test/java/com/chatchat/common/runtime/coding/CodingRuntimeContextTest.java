package com.chatchat.common.runtime.coding;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.chatchat.common.runtime.coding.CodingContractTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class CodingRuntimeContextTest {
    @Test void legacyContractCanonicalBytesAndHashRemainStable() throws Exception {
        var task = JSON.treeToValue(fixture("task"), CodingTaskContract.class);
        assertThat(task.runtimeContext()).isEmpty();
        assertThat(CodingCanonicalHash.canonicalJson(task)).doesNotContain("runtimeContext");
        assertThat(CodingCanonicalHash.sha256(task)).isEqualTo(CodingCanonicalHash.sha256(JSON.treeToValue(tree(task),CodingTaskContract.class)));
    }
    @Test void contextIsImmutableAndParticipatesInContractHash() throws Exception {
        var original = JSON.treeToValue(fixture("task"),CodingTaskContract.class);
        var node = tree(original);
        node.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1",
            "consistency","TASK_SNAPSHOT","model",Map.of("selectedModel","authorized-model"))));
        var updated = JSON.treeToValue(node,CodingTaskContract.class);
        assertThat(CodingCanonicalHash.sha256(updated)).isNotEqualTo(CodingCanonicalHash.sha256(original));
        assertThatThrownBy(() -> ((Map<String,Object>)updated.runtimeContext().get("model")).put("selectedModel","other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void credentialAndArbitraryConfigurationCannotCrossTheContract() {
        for (String key : List.of("apiKey","password","baseUrl","authorization"))
            assertThatThrownBy(() -> CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1",
                "consistency","TASK_SNAPSHOT","model",Map.of(key,"private")))).isInstanceOf(CodingContractException.class);
    }
}
