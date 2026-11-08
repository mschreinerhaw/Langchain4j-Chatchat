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
    @Test void directAssetTaskReadsItsExistingDurableContractWithoutInventingAnAgentParent(){
        var runtime=mock(AgentRuntime.class);var tasks=mock(DurableCodingTaskService.class);var coding=mock(CodingAgentSPI.class);
        var scope=new com.chatchat.common.kernel.KernelDataScope("tenant","owner",null,null,"asset-run",null,Map.of());
        var contract=mock(com.chatchat.common.runtime.coding.CodingTaskContract.class,RETURNS_DEEP_STUBS);
        when(contract.scope()).thenReturn(scope);when(contract.objective().instruction()).thenReturn("Count actual CSV rows");
        var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.FAILED);
        when(tasks.contract(any(),eq("task"))).thenReturn(contract);when(tasks.get(any(),eq("task"))).thenReturn(outcome);
        var lease=mock(com.chatchat.agents.runtime.coding.CodingJournal.Lease.class);when(tasks.ownership(any(),eq("task"))).thenReturn(lease);
        var controller=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext());
        var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
        assertThat(((Map<?,?>)controller.task("asset-run","task",request).getData()).get("outcome")).isSameAs(outcome);
        for(var actor:List.of(List.of("tenant","other"),List.of("other","owner"))){
            request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,actor.get(0));request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,actor.get(1));
            assertThatThrownBy(()->controller.task("asset-run","task",request)).isInstanceOf(ResponseStatusException.class);
        }
        verify(tasks,times(1)).get(any(),eq("task"));verifyNoInteractions(coding);verify(runtime,never()).observations(anyString());
    }
    @Test void failedInputPreparationDoesNotInventAnAdmittedCodingTaskOrBreakOperationalStatus() {
        var runtime=mock(AgentRuntime.class);var tasks=mock(DurableCodingTaskService.class);var coding=mock(CodingAgentSPI.class);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        var observation=mock(com.chatchat.agents.runtime.observation.AgentObservation.class);
        when(observation.metadata()).thenReturn(Map.of("eventKind","CODING_INPUT_PREPARING","taskId","not-admitted"));
        when(runtime.observations("run")).thenReturn(List.of(observation));
        when(tasks.get(any(),eq("not-admitted"))).thenThrow(new com.chatchat.common.runtime.coding.CodingContractException(
            com.chatchat.common.runtime.coding.CodingErrorCode.NOT_FOUND,"Coding task not found in authorized scope"));
        var controller=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext());
        var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
        assertThat(controller.status("run",request).getData().tasks()).isEmpty();
        assertThat(((Map<?,?>)controller.list("run",request).getData()).get("tasks")).isEqualTo(List.of());
        when(tasks.get(any(),eq("not-admitted"))).thenThrow(new com.chatchat.common.runtime.coding.CodingContractException(
            com.chatchat.common.runtime.coding.CodingErrorCode.AUTHORIZATION_DENIED,"Scope denied"));
        assertThatThrownBy(()->controller.status("run",request)).hasMessage("Scope denied");
        verify(tasks,never()).contract(any(),anyString());verifyNoInteractions(coding);
    }
    @Test void tenantMonitorReadsOnlyDurableOperationalFactsWithReadOnlyOwnerScope() throws Exception {
        var runtime=mock(AgentRuntime.class); var tasks=mock(DurableCodingTaskService.class); var coding=mock(CodingAgentSPI.class);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        var observation=mock(com.chatchat.agents.runtime.observation.AgentObservation.class);
        when(observation.metadata()).thenReturn(Map.of("eventKind","CODING_TRACE","taskId","task"));
        when(runtime.observations("run")).thenReturn(List.of(observation,observation));
        var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
        when(outcome.taskId()).thenReturn("task");when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.FAILED);
        when(outcome.summary()).thenReturn("PRIVATE_SOURCE_TEXT");
        when(tasks.get(any(),eq("task"))).thenAnswer(call->{
            assertThat(((com.chatchat.common.kernel.KernelDataScope)call.getArgument(0)).userId()).isEqualTo("owner");return outcome;
        });
        var admin=mock(com.chatchat.enterprise.service.EnterpriseAdminService.class);
        when(admin.hasPermission("monitor","platform:tasks:all")).thenReturn(true);
        var controller=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext());
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"enterpriseAdmin",admin);
        var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"monitor");
        var result=controller.status("run",request).getData();
        assertThat(result.detailOwner()).isFalse();assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).status()).isEqualTo(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.FAILED);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)).doesNotContain("PRIVATE_SOURCE_TEXT","objective","configuration","evidenceRefs","inputSnapshotRef");
        verify(tasks,never()).contract(any(),anyString());verifyNoInteractions(coding);
        assertThatThrownBy(()->controller.list("run",request)).isInstanceOf(ResponseStatusException.class);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"other");
        assertThatThrownBy(()->controller.status("run",request)).isInstanceOf(ResponseStatusException.class);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"intruder");
        assertThatThrownBy(()->controller.status("run",request)).isInstanceOf(ResponseStatusException.class);
        verify(tasks,times(1)).get(any(),eq("task"));
    }
    @Test void completedTaskProjectsDurableModelReceiptsWithoutRequiringNodeCompletionEvents() {
        var runtime=mock(AgentRuntime.class);var tasks=mock(DurableCodingTaskService.class);var coding=mock(CodingAgentSPI.class);
        when(runtime.find("run")).thenReturn(Optional.of(AgentRun.builder().runId("run")
            .request(AgentRunRequest.builder().tenantId("tenant").userId("owner").build()).build()));
        var observation=mock(com.chatchat.agents.runtime.observation.AgentObservation.class);
        when(observation.metadata()).thenReturn(Map.of("eventKind","CODING_TRACE","taskId","task"));
        when(runtime.observations("run")).thenReturn(List.of(observation));
        var contract=mock(com.chatchat.common.runtime.coding.CodingTaskContract.class);
        when(contract.objective()).thenReturn(new com.chatchat.common.runtime.coding.CodingTaskContract.Objective("count rows",List.of("rows.jsonl"),List.of("criteria")));
        when(contract.runtimeContext()).thenReturn(Map.of());when(contract.authorization()).thenReturn(
            new com.chatchat.common.runtime.coding.CodingTaskContract.Authorization("decision",List.of(),List.of(),List.of(),List.of(),
                com.chatchat.common.runtime.coding.CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of()));
        var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
        when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.COMPLETED);
        when(tasks.get(any(),eq("task"))).thenReturn(outcome);when(tasks.contract(any(),eq("task"))).thenReturn(contract);
        var lease=mock(com.chatchat.agents.runtime.coding.CodingJournal.Lease.class);when(lease.fencingToken()).thenReturn(1L);
        when(tasks.ownership(any(),eq("task"))).thenReturn(lease);
        when(tasks.startedModelOperations(any(),eq("task"))).thenReturn(List.of(new DurableCodingTaskService.StartedModelOperation("model-2",1)));
        var receipts=mock(com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore.class);
        var model=new com.chatchat.common.runtime.coding.CodingModelCallTelemetry("model-2","task:fence-1",2,"SUCCEEDED",
            "alias","actual-model","openai","start","end",1000L,Map.of("inputTokens",12L),"JAVA_MODEL_RECEIPT");
        when(receipts.telemetry(contract,"model-2",1)).thenReturn(Optional.of(model));
        var controller=new CodingTaskMonitorController(runtime,tasks,coding,new CodingInvocationContext());
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"modelReceipts",receipts);
        var request=new MockHttpServletRequest();request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
        var result=(Map<?,?>)controller.list("run",request).getData();
        var item=(Map<?,?>)((List<?>)result.get("tasks")).get(0);
        assertThat(item.get("modelReceipts")).isEqualTo(List.of(model));
        verify(tasks,never()).events(any(),anyString(),anyLong(),anyInt());
    }
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
            assertThatThrownBy(()->controller.inputFiles("run","task",null,request)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->controller.reviewVisualReport("run","task","runtime-evidence:result",request)).isInstanceOf(ResponseStatusException.class);
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
