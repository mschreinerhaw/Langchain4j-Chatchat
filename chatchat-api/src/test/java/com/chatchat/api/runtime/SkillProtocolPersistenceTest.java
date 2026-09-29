package com.chatchat.api.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes=SkillProtocolPersistenceTest.Config.class)
@ActiveProfiles("skill-protocol-persistence-test")
class SkillProtocolPersistenceTest {
    @Autowired SkillDataBindingService bindings;
    @Autowired SkillAnalysisRunService runs;
    @Test void databasePersistsPublishedSnapshotAndOptimisticRevisions() {
        var binding=new SkillDataWorkflowProperties.Binding(true,"tenant","domain","sales.rows.v1","fixed","v1",
            "execution","template","asset","prod",Map.of(),Map.of("amount","value"),Map.of());
        var saved=bindings.draft("tenant",binding,null);
        long revision=saved.getRevision();
        var published=bindings.publish("tenant","domain","sales.rows.v1",revision,"admin");
        assertThat(published.getRevision()).isGreaterThan(revision);
        assertThat(bindings.effective("tenant","domain",List.of())).containsExactly(binding);
        assertThat(bindings.effective("other","domain",List.of())).isEmpty();
        assertThatThrownBy(() -> bindings.publish("tenant","domain","sales.rows.v1",revision,"admin"))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void databasePersistsRunAndExplicitReview() {
        var identity=new com.chatchat.runtime.skill.api.identity.SkillRoleContext("tenant","user",List.of(),List.of(),Map.of());
        String id=runs.save(identity,Map.of("query","q"),Map.of("status","COMPLETED"));
        assertThat(runs.read(id,identity)).containsKey("result");
        var reviewed=runs.review(id,identity,"NEEDS_REVIEW","Inspect source",0);
        assertThat(reviewed.get("review").toString()).contains("NEEDS_REVIEW");
        assertThat(((Number)reviewed.get("revision")).longValue()).isGreaterThan(0);
    }
    @Configuration
    @Profile("skill-protocol-persistence-test")
    @EntityScan(basePackageClasses=SkillDataBindingEntity.class)
    @EnableJpaRepositories(basePackageClasses=SkillDataBindingRepository.class)
    @Import({SkillDataBindingService.class,SkillAnalysisRunService.class})
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
    }
}

