package com.chatchat.chat.analysis.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class DomainProfileDefaultsTest {

    @Test
    void bundledDefaultsDoNotPrescribeReportSectionTitles() throws Exception {
        try (var input = getClass().getResourceAsStream("/analysis/domain-profile-defaults.json")) {
            assertThat(input).isNotNull();
            var defaults = new ObjectMapper().readTree(input);
            assertThat(StreamSupport.stream(defaults.spliterator(), false)).allSatisfy(profile ->
                assertThat(profile.path("guidance").has("sectionTitles")).isFalse());
        }
    }
}
