package com.chatchat.chat.analysis.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = DomainAnalysisProfilePersistenceTest.Config.class)
class DomainAnalysisProfilePersistenceTest {
    @Configuration @EntityScan(basePackageClasses = DomainAnalysisProfileEntity.class)
    @EnableJpaRepositories(basePackageClasses = DomainAnalysisProfileRepository.class)
    @Import(DomainAnalysisProfileService.class)
    static class Config { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @Autowired DomainAnalysisProfileService service;
    @Autowired DomainAnalysisProfileRepository repository;

    @Test void persistsTitlesRevisionsAndTenantDisableWithoutResettingDefaults() {
        service.initializeDefaults();
        assertThat(repository.count()).isEqualTo(3);
        var inherited = service.list("tenant-a").stream().filter(p -> p.analysisType().equals("FINANCIAL_ASSETS")).findFirst().orElseThrow();
        var guidance = new java.util.LinkedHashMap<>(inherited.guidance());
        guidance.put("sectionTitles", Map.of("KEY_DRIVERS", "数据库维护的新标题"));
        var first = service.update("tenant-a", inherited.analysisType(), new DomainAnalysisProfileService.Update(
            inherited.name(), inherited.description(), true, null, guidance));
        var second = service.update("tenant-a", inherited.analysisType(), new DomainAnalysisProfileService.Update(
            first.name(), first.description(), false, first.revision(), first.guidance()));
        assertThat(second.revision()).isGreaterThan(first.revision());
        service.initializeDefaults();
        assertThat(repository.count()).isEqualTo(4);
        assertThat(service.profiles("tenant-a")).noneMatch(p -> p.analysisType().equals("FINANCIAL_ASSETS"));
        assertThat(service.profiles("tenant-b")).hasSize(3);
        assertThat(service.list("tenant-a").stream().filter(p -> p.analysisType().equals("FINANCIAL_ASSETS")).findFirst().orElseThrow().guidance())
            .containsEntry("sectionTitles", Map.of("KEY_DRIVERS", "数据库维护的新标题"));
    }
}
