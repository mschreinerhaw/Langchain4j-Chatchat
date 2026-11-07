package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP routing and authenticated scope are exercised without relying on compiler parameter metadata. */
class NativeCodingControllerHttpTest {
    final String root="/api/v1/coding/projects",task="native-"+"a".repeat(48);
    NativeCodingTaskService service;NativeCodingWorkspaceService workspace;DurableCodingTaskService tasks;
    CodingAgentSPI agent;CodingInvocationContext identity;MockMvc mvc;
    @BeforeEach void setup(){
        service=mock(NativeCodingTaskService.class);workspace=mock(NativeCodingWorkspaceService.class);
        tasks=mock(DurableCodingTaskService.class);agent=mock(CodingAgentSPI.class);identity=new CodingInvocationContext();
        when(tasks.get(any(),anyString())).thenAnswer(call->{
            identity.require(call.getArgument(0),CodingTaskAccess.Action.READ);return mock(CodingTaskOutcome.class);
        });
        when(tasks.contract(any(),anyString())).thenReturn(mock(CodingTaskContract.class));
        mvc=MockMvcBuilders.standaloneSetup(new NativeCodingController(service,workspace,tasks,agent,identity)).build();
    }
    MockHttpServletRequestBuilder owner(MockHttpServletRequestBuilder request){
        return request.requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant")
            .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID,"owner");
    }
    @Test void submissionBindsRequestIdAndUsesOnlyAuthenticatedOwner()throws Exception{
        when(service.submit(any(),anyString(),any())).thenAnswer(call->{
            KernelDataScope scope=call.getArgument(0);identity.require(scope,CodingTaskAccess.Action.SUBMIT);
            assertThat(scope.tenantId()).isEqualTo("tenant");assertThat(scope.userId()).isEqualTo("owner");
            assertThat(scope.runId()).isEqualTo(NativeCodingController.taskId(List.of("tenant","owner"),"http-case"));return null;
        });
        mvc.perform(owner(put(root+"/http-case")).contentType("application/json")
            .content("{\"instruction\":\"repair project\",\"files\":{},\"tenantId\":\"forged\",\"userId\":\"forged\"}"))
            .andExpect(status().isOk());
        verify(service).submit(any(),eq(NativeCodingController.taskId(List.of("tenant","owner"),"http-case")),any());
    }
    @ParameterizedTest @ValueSource(strings={"","/files","/history","/diff/1","/events"})
    void readRoutesBindNamedPathArguments(String suffix)throws Exception{
        mvc.perform(owner(get(root+"/"+task+suffix))).andExpect(status().isOk());
        assertThatThrownBy(()->identity.require(new KernelDataScope("tenant","owner",null,null,task,null,java.util.Map.of()),CodingTaskAccess.Action.READ))
            .isInstanceOf(CodingContractException.class);
    }
    @ParameterizedTest @ValueSource(strings={"resume","cancel"})
    void actionRoutesBindTheSameTask(String action)throws Exception{
        mvc.perform(owner(post(root+"/"+task+"/"+action))).andExpect(status().isOk());
        if(action.equals("resume"))verify(agent).resume(any(),eq(task));else verify(agent).cancel(any(),eq(task),anyString());
    }
    @Test void absentIdentityCannotReadOrTriggerTaskAction()throws Exception{
        mvc.perform(get(root+"/"+task)).andExpect(status().isForbidden());
        mvc.perform(post(root+"/"+task+"/resume")).andExpect(status().isForbidden());
        verifyNoInteractions(tasks,agent,service,workspace);
    }
    @Test void malformedVersionIsRejectedBeforeWorkspaceRead()throws Exception{
        mvc.perform(owner(get(root+"/"+task+"/diff/not-a-number"))).andExpect(status().isBadRequest());
        verifyNoInteractions(workspace);
    }
}
