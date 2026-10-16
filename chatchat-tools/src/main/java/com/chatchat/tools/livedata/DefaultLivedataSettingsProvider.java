package com.chatchat.tools.livedata;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultLivedataSettingsProvider implements LivedataSettingsProvider {

    private final LivedataAutoRegistrationProperties properties;

    @Override
    public LivedataAutoRegistrationProperties current() {
        return properties;
    }
}
