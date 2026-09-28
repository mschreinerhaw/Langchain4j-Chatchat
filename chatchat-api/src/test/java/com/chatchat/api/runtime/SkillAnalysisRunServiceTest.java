package com.chatchat.api.runtime;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillAnalysisRunServiceTest {
    @Test void reviewIsExplicitHumanDecisionWithTenantAndOwnerChecks() {
        var repository=mock(SkillAnalysisRunRepository.class);
        var service=new SkillAnalysisRunService(repository,new ObjectMapper());
        var identity=new SkillRoleContext("t","u",List.of(),List.of(),Map.of());
        var captured=org.mockito.ArgumentCaptor.forClass(SkillAnalysisRunEntity.class);
        String id=service.save(identity,Map.of("query","q"),Map.of("status","COMPLETED_WITH_LIMITATIONS"));
        verify(repository).save(captured.capture());var entity=captured.getValue();
        assertThat(entity.getReviewJson()).isNull();
        when(repository.findByIdAndTenantIdAndUserId(id,"t","u")).thenReturn(Optional.of(entity));
        service.review(id,identity,"NEEDS_REVIEW","Source needs inspection",0);
        assertThat(entity.getReviewJson()).contains("NEEDS_REVIEW","reviewerId");
        assertThatThrownBy(() -> service.read(id,new SkillRoleContext("other","u",List.of(),List.of(),Map.of())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}

