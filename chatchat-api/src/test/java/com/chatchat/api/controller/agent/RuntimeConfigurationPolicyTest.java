package com.chatchat.api.controller.agent;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class RuntimeConfigurationPolicyTest {
    @Test void customerCannotRaiseBudgetsOrInjectSandboxConfiguration() {
        var saved=Map.<String,Object>of("executionStrategy",Map.of("maxSteps",4),"sandbox",Map.of("memory","512m"));
        var result=RuntimeConfigurationPolicy.forUser(Map.of("executionStrategy",Map.of("maxSteps",9999),"sandbox",Map.of("privileged",true),"plugins","arbitrary","steps",java.util.List.of("approved")),saved);
        assertThat(result.get("executionStrategy")).isEqualTo(saved.get("executionStrategy"));
        assertThat(result.get("sandbox")).isEqualTo(saved.get("sandbox"));assertThat(result).doesNotContainKey("plugins");
        assertThat(result).containsKey("steps");
    }
    @Test void newServicesUseDefaultsWithoutDroppingBusinessToolBindings() {
        var result=RuntimeConfigurationPolicy.forUser(Map.of("requiredToolParameters",Map.of("tool","value")),null);
        assertThat(result).containsKey("requiredToolParameters").containsKey("executionStrategy");
    }
}
