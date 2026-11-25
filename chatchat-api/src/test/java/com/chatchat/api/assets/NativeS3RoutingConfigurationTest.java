package com.chatchat.api.assets;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.nativecline.NativeAwareCodingAdapter;
import com.chatchat.api.runtime.coding.nativecline.NativeS3CodingAdapter;
import com.chatchat.api.runtime.coding.nativecline.NativeS3RoutingConfiguration;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NativeS3RoutingConfigurationTest {
    @Test
    void selectsASinglePrimaryRouterWhenOnlyUnifiedNativeIsEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(NativeS3RoutingConfiguration.class)
            .withInitializer(context -> {
                context.getBeanFactory().registerSingleton("codingTaskService", mock(DurableCodingTaskService.class));
                context.getBeanFactory().registerSingleton("nativeS3CodingAdapter", mock(NativeS3CodingAdapter.class));
            })
            .withPropertyValues("chatchat.coding.enabled=true", "chatchat.runtime.coding.unified.enabled=true");
        runner.run(context -> assertThat(context.getBean(CodingAgentSPI.class)).isInstanceOf(NativeAwareCodingAdapter.class));
        runner.withPropertyValues("chatchat.coding.cline.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean("nativeS3CodingAgent"));
    }
}
