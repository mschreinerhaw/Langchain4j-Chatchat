package com.chatchat.tools.livedata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Register the fallback at bean-method time so component scans cannot match it against itself. */
@Configuration(proxyBeanMethods = false)
public class LivedataSettingsConfiguration {
    @Bean
    @ConditionalOnMissingBean(LivedataSettingsProvider.class)
    LivedataSettingsProvider defaultLivedataSettingsProvider(LivedataAutoRegistrationProperties properties) {
        return new DefaultLivedataSettingsProvider(properties);
    }
}
