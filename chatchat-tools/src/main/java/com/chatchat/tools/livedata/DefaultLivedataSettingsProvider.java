package com.chatchat.tools.livedata;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnMissingBean(LivedataSettingsProvider.class)
public class DefaultLivedataSettingsProvider implements LivedataSettingsProvider {

    private final LivedataAutoRegistrationProperties properties;

    @Override
    public LivedataAutoRegistrationProperties current() {
        return properties;
    }
}
