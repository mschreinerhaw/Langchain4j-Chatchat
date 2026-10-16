package com.chatchat.tools.livedata;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class LivedataSettingsConfigurationTest {
    @Test void fallbackExistsWithoutMatchingItsOwnConditionalComponent(){
        try(var context=new AnnotationConfigApplicationContext()){
            var properties=new LivedataAutoRegistrationProperties();
            context.registerBean(LivedataAutoRegistrationProperties.class,()->properties);
            context.register(LivedataSettingsConfiguration.class);context.refresh();
            assertThat(context.getBeansOfType(LivedataSettingsProvider.class)).hasSize(1);
            assertThat(context.getBean(LivedataSettingsProvider.class).current()).isSameAs(properties);
        }
    }
    @Test void explicitProviderStillOverridesTheFallback(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(LivedataAutoRegistrationProperties.class,LivedataAutoRegistrationProperties::new);
            LivedataSettingsProvider custom=LivedataAutoRegistrationProperties::new;
            context.registerBean("customSettings",LivedataSettingsProvider.class,()->custom);
            context.register(LivedataSettingsConfiguration.class);context.refresh();
            assertThat(context.getBeansOfType(LivedataSettingsProvider.class)).hasSize(1);
            assertThat(context.getBean(LivedataSettingsProvider.class)).isSameAs(custom);
        }
    }
}
