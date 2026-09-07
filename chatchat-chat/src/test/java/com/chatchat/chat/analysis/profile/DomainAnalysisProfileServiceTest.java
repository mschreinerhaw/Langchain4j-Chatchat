package com.chatchat.chat.analysis.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DomainAnalysisProfileServiceTest {
    private final DomainAnalysisProfileRepository repository = mock(DomainAnalysisProfileRepository.class);
    private final DomainAnalysisProfileService service = new DomainAnalysisProfileService(repository, new ObjectMapper());
    private DomainAnalysisProfileEntity row(String owner, boolean enabled) {
        var row = new DomainAnalysisProfileEntity();
        row.setId(owner + ":RETAIL"); row.setTenantId(owner); row.setAnalysisType("RETAIL");
        row.setName("零售"); row.setDescription("零售分析"); row.setEnabled(enabled); row.setRevision(2L);
        row.setGuidanceJson("{\"focus\":[\"门店表现\"],\"output\":[\"KEY_FINDINGS\"],\"sectionTitles\":{\"KEY_FINDINGS\":\"经营分析\"}}");
        return row;
    }
    @Test void disabledTenantOverrideSuppressesGlobalProfileAndOtherTenantsKeepTheDefault() {
        when(repository.findByTenantIdOrderByAnalysisType("__global__")).thenReturn(List.of(row("__global__", true)));
        when(repository.findByTenantIdOrderByAnalysisType("a")).thenReturn(List.of(row("a", false)));
        assertThat(service.profiles("a")).isEmpty();
        assertThat(service.profiles("b")).hasSize(1);
        assertThat(service.list("a").get(0).source()).isEqualTo("TENANT");
    }
    @Test void validatesUpdatesAndRejectsStaleRevisionsAndExecutionDirectives() {
        when(repository.findById("a:RETAIL")).thenReturn(Optional.of(row("a", true)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> guidance = Map.of("focus", List.of("新视角"), "output", List.of("KEY_FINDINGS"),
            "sectionTitles", Map.of("KEY_FINDINGS", "新业务标题"));
        var updated = service.update("a", "RETAIL", new DomainAnalysisProfileService.Update("零售", "新描述", true, 2L, guidance));
        assertThat(updated.guidance().get("sectionTitles")).isEqualTo(Map.of("KEY_FINDINGS", "新业务标题"));
        assertThatThrownBy(() -> service.update("a", "RETAIL", new DomainAnalysisProfileService.Update("零售", "描述", true, 1L, guidance)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("版本");
        assertThatThrownBy(() -> service.update("a", "RETAIL", new DomainAnalysisProfileService.Update("零售", "描述", true, 2L, Map.of("sql", "DROP"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void defaultDataIsValidatedAndExistingDatabaseRowsAreNotOverwritten() {
        service.initializeDefaults();
        verify(repository, times(3)).save(any());
        clearInvocations(repository);
        when(repository.existsById(anyString())).thenReturn(true);
        service.initializeDefaults();
        verify(repository, never()).save(any());
    }
}
