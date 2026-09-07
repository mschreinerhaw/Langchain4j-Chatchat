package com.chatchat.chat.analysis.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class DomainAnalysisProfileInitializer implements ApplicationRunner {
    private final DomainAnalysisProfileService profiles;
    @Override public void run(ApplicationArguments arguments) { profiles.initializeDefaults(); }
}
