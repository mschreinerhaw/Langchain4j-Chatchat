package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.runtime.coding.unified", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NativeS3RoutingConfiguration {
    @Bean
    @Primary
    CodingAgentSPI nativeS3CodingAgent(DurableCodingTaskService tasks, NativeS3CodingAdapter nativeS3) {
        return new NativeAwareCodingAdapter(tasks, nativeS3, tasks);
    }
}
