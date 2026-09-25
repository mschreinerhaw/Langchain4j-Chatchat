package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEvidenceArchiveControllerTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Test void onlyAuthenticatedOwnerCanReadArchive() {
        AnalysisEvidenceArchivePort archive = new AnalysisEvidenceArchivePort() {
            @Override public Reference archive(com.chatchat.common.runtime.analysis.model.AnalysisContext context,
                                               com.chatchat.common.runtime.analysis.evidence.EvidenceBundle bundle) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<ArchivedEvidence> read(String tenant, String user, String id) {
                return "tenant".equals(tenant) && "user".equals(user) && ID.equals(id)
                    ? Optional.of(new ArchivedEvidence(new Reference(ID, "sha", 2), "{}")) : Optional.empty();
            }
        };
        var controller = new AgentEvidenceArchiveController(archive, new ObjectMapper());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user");

        assertThat(controller.get(ID, request).getData().bundle().isObject()).isTrue();
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("other");
        assertThatThrownBy(() -> controller.get(ID, request))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
