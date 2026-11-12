package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LightCodingMonitorControlTest {
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final CodingAgentSPI coding=mock(CodingAgentSPI.class);
    final CodingInvocationContext identity=new CodingInvocationContext();
    final KernelDataScope owner=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    @SuppressWarnings("unchecked") final LightCodingMonitorController controller=new LightCodingMonitorController(
        tasks,identity,null,null,mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class),coding);
    MockHttpServletRequest request(String tenant,String user){var r=new MockHttpServletRequest();if(tenant!=null)r.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,tenant);if(user!=null)r.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,user);return r;}
    void contract(){var c=mock(CodingTaskContract.class);when(c.scope()).thenReturn(owner);when(tasks.contract(any(),eq("task"))).thenReturn(c);}
    CodingTaskOutcome outcome(CodingTaskOutcome.Status status){var o=mock(CodingTaskOutcome.class);when(o.status()).thenReturn(status);return o;}
    @Test void ownerStopRoutesThroughSpiAndReconcilesWithoutSubmittingAnotherTask(){
        contract();var running=outcome(CodingTaskOutcome.Status.RUNNING);var cancelled=outcome(CodingTaskOutcome.Status.CANCELLED);
        when(tasks.get(any(),eq("task"))).thenReturn(running,running,cancelled);
        when(coding.cancel(any(),eq("task"),anyString())).thenAnswer(call->{identity.require(call.getArgument(0),CodingTaskAccess.Action.STOP);return running;});
        var result=(Map<?,?>)controller.cancel(request("tenant","owner"),"run","task").getData();
        assertThat(result.get("stopState")).isEqualTo("TERMINAL");assertThat(result.get("outcome")).isSameAs(cancelled);
        verify(coding).resume(any(),eq("task"));verify(coding,never()).submit(any());
    }
    @Test void unknownStopRemainsPendingEvenWhenNodeRecoveryFails(){
        contract();var running=outcome(CodingTaskOutcome.Status.RUNNING);when(tasks.get(any(),eq("task"))).thenReturn(running);
        when(coding.resume(any(),eq("task"))).thenThrow(new IllegalStateException("Node unavailable"));
        var result=(Map<?,?>)controller.cancel(request("tenant","owner"),"run","task").getData();
        assertThat(result.get("stopState")).isEqualTo("STOPPING");verify(coding).cancel(any(),eq("task"),anyString());
    }
    @Test void alreadyTerminalTaskIsIdempotentAndNeverTouchesNode(){
        contract();var completed=outcome(CodingTaskOutcome.Status.COMPLETED);when(tasks.get(any(),eq("task"))).thenReturn(completed);
        controller.cancel(request("tenant","owner"),"run","task");verifyNoInteractions(coding);
    }
    @Test void tenantUserAndRunMismatchAreDeniedBeforeAnyCancellation(){
        contract();
        for(var pair:List.of(List.of("other","owner","run"),List.of("tenant","other","run"),List.of("tenant","owner","other")))
            assertThatThrownBy(()->controller.cancel(request(pair.get(0),pair.get(1)),pair.get(2),"task")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.cancel(request(null,null),"run","task")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(coding);verify(tasks,never()).get(any(),anyString());
    }
}
