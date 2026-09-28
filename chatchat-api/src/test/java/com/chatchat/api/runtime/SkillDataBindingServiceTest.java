package com.chatchat.api.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillDataBindingServiceTest {
    private final SkillDataBindingRepository repository=mock(SkillDataBindingRepository.class);
    private final SkillDataBindingService service=new SkillDataBindingService(repository,new ObjectMapper());
    @Test void draftChangesDoNotExecuteUntilExplicitVersionedPublicationAndRetirementDoesNotReactivateConfig() {
        when(repository.findByTenantIdAndDomainSkillIdAndContractId("t","s","data.rows.v1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var entity=service.draft("t",binding("v1"),null);
        when(repository.findByTenantIdAndDomainSkillIdAndContractId("t","s","data.rows.v1")).thenReturn(Optional.of(entity));
        when(repository.findByTenantIdAndDomainSkillId("t","s")).thenReturn(List.of(entity));
        assertThat(service.effective("t","s",List.of())).isEmpty();
        service.publish("t","s","data.rows.v1",0,"admin");
        service.draft("t",binding("v2"),0L);
        assertThat(service.effective("t","s",List.of())).extracting(SkillDataWorkflowProperties.Binding::version).containsExactly("v1");
        service.publish("t","s","data.rows.v1",0,"admin");
        assertThat(service.effective("t","s",List.of())).extracting(SkillDataWorkflowProperties.Binding::version).containsExactly("v2");
        service.retire("t","s","data.rows.v1",0);
        assertThat(service.effective("t","s",List.of(binding("v0")))).isEmpty();
    }
    @Test void rejectsTenantSpoofingAndStaleRevision() {
        assertThatThrownBy(() -> service.draft("other",binding("v1"),null)).isInstanceOf(IllegalArgumentException.class);
        var entity=new SkillDataBindingEntity();entity.setRevision(2);
        when(repository.findByTenantIdAndDomainSkillIdAndContractId("t","s","data.rows.v1")).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.draft("t",binding("v2"),1L)).isInstanceOf(IllegalStateException.class);
        verify(repository,never()).saveAndFlush(any());
    }
    private SkillDataWorkflowProperties.Binding binding(String version) {
        return new SkillDataWorkflowProperties.Binding(true,"t","s","data.rows.v1","fixed",version,"execution","template","asset","prod",
            Map.of(),Map.of("value","amount"),Map.of());
    }
}

