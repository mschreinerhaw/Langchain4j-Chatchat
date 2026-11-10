package com.chatchat.api.runtime.coding.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class CodingLimitBindingTest {
    @Test void unlimitedBindsOnlyToExplicitCodingLimitsAndKeepsIsolationSettings() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CodingAnalysisProperties.class,CodingLimitConverter.class)
            .withPropertyValues("chatchat.coding.analysis.max-model-calls=unlimited","chatchat.coding.analysis.max-tool-calls=unlimited",
                "chatchat.coding.analysis.max-code-executions=unlimited","chatchat.coding.analysis.max-model-tokens=unlimited",
                "chatchat.coding.analysis.max-cumulative-cpu-millis=unlimited","chatchat.coding.analysis.max-cumulative-output-bytes=unlimited")
            .run(context->{
                assertThat(context).hasNotFailed();var p=context.getBean(CodingAnalysisProperties.class);
                assertThat(p.getMaxModelCalls().value()).isEqualTo(-1);assertThat(p.getMaxToolCalls().value()).isEqualTo(-1);
                assertThat(p.getMaxCodeExecutions().value()).isEqualTo(-1);assertThat(p.getMaxModelTokens().value()).isEqualTo(-1);
                assertThat(p.getMaxCumulativeCpuMillis().value()).isEqualTo(-1);assertThat(p.getMaxCumulativeOutputBytes().value()).isEqualTo(-1);
                assertThat(p.getMaxMemoryBytes()).isEqualTo(512L*1024*1024);assertThat(p.getMaxResultBytes()).isEqualTo(4L*1024*1024);
            });
    }
}
