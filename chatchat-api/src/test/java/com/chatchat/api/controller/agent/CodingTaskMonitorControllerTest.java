package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.*;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingTaskMonitorControllerTest {
    @Test void everyMonitorOperationRejectsOtherUsersAndTenantsBeforeJournalAccess() {
        var runtime=mock(AgentRuntime.class); var tasks=mock(DurableCodingTaskService.class); var coding=mock(CodingAgentSPI.class);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        var controller=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext());
        for(var actor:List.of(List.of("tenant","intruder"),List.of("other","owner"))) {
            var request=new MockHttpServletRequest();
            request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,actor.get(0));
            request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,actor.get(1));
            assertThatThrownBy(()->controller.list("run",request)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->controller.events("run","task",0,128,request)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->controller.cancel("run","task",request)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->controller.resume("run","task",request)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(tasks,coding);
    }
    @Test void authenticatedQaRunHasNoInventedCodingTasks() {
        var runtime=mock(AgentRuntime.class);var tasks=mock(DurableCodingTaskService.class);var coding=mock(CodingAgentSPI.class);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        when(runtime.observations("run")).thenReturn(List.of());
        var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
        var result=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext()).list("run",request);
        assertThat(((Map<?,?>)result.getData()).get("tasks")).isEqualTo(List.of());verifyNoInteractions(tasks,coding);
    }
}
