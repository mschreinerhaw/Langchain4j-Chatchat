package com.chatchat.tools.livedata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LivedataAutoRegistrationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(LivedataAutoRegistrationProperties.class);

    @Test
    void bindsToolConfigurationPrefixUsedByMcpServerYaml() {
        contextRunner
            .withPropertyValues(
                "chatchat.tools.livedata.enabled=true",
                "chatchat.tools.livedata.service-base-url=http://livedata.internal"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LivedataAutoRegistrationProperties.class);
                LivedataAutoRegistrationProperties properties =
                    context.getBean(LivedataAutoRegistrationProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getServiceBaseUrl()).isEqualTo("http://livedata.internal");
            });
    }
}
