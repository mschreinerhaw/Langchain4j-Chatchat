package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.model.PlatformInternetPolicy;
import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.common.retrieval.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingInternetAccessTest {
    final ObjectMapper json=new ObjectMapper();
    final PlatformInternetPolicy models=mock(PlatformInternetPolicy.class);
    final AgentResourceScopePort agents=mock(AgentResourceScopePort.class);
    final CodingTaskContract contract=mock(CodingTaskContract.class,RETURNS_DEEP_STUBS);
    final CodingInternetAccess internet=new CodingInternetAccess(models,agents,mock(McpControlPlaneClient.class),json);
    @BeforeEach void configure()throws Exception{
        when(models.read("model-a")).thenReturn(new PlatformInternetPolicy.Policy(true,"provider",3));when(models.active("model-a")).thenReturn(true);
        when(models.providers()).thenReturn(json.readTree("[{\"id\":\"provider\",\"enabled\":true,\"configured\":true,\"revision\":7}]"));
        when(agents.resolve(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(Set.of(AgentResourcePolicy.INTERNET));
        when(contract.scope().tenantId()).thenReturn("tenant");when(contract.scope().userId()).thenReturn("user");
        when(contract.authorization().allowedTools()).thenReturn(List.of("web_search","web_fetch"));
        when(contract.authorization().networkPolicy()).thenReturn(CodingTaskContract.NetworkPolicy.NONE);
        when(contract.runtimeContext()).thenReturn(Map.of("model",Map.of("selectedModel","model-a"),"internetSearch",Map.of("modelName","model-a","modelRevision",3L,"providerId","provider","providerRevision",7L,"agentId","agent","explicitlyRequested",false)));
    }
    @Test void allowsOnlyCurrentIntersectedAuthority(){assertThatCode(()->internet.require(contract,"web_search")).doesNotThrowAnyException();assertThatThrownBy(()->internet.require(contract,"shell")).isInstanceOf(SecurityException.class);}
    @Test void offThenOnCannotReviveOldTasks(){
        when(models.read("model-a")).thenReturn(new PlatformInternetPolicy.Policy(false,"provider",4));
        assertThatThrownBy(()->internet.require(contract,"web_search")).isInstanceOf(SecurityException.class);
        when(models.read("model-a")).thenReturn(new PlatformInternetPolicy.Policy(true,"provider",5));
        assertThatThrownBy(()->internet.require(contract,"web_search")).isInstanceOf(SecurityException.class);
    }
    @Test void liveUserGrantRevocationBlocksPreviouslyGrantedTask(){
        when(agents.resolve(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(Set.of());
        assertThatThrownBy(()->internet.require(contract,"web_fetch")).isInstanceOf(SecurityException.class);
    }
    @Test void providerRevisionAndModelPublicationAreLive()throws Exception{
        when(models.providers()).thenReturn(json.readTree("[{\"id\":\"provider\",\"enabled\":true,\"configured\":true,\"revision\":8}]"));
        assertThatThrownBy(()->internet.require(contract,"web_search")).isInstanceOf(SecurityException.class);
        when(models.active("model-a")).thenReturn(false);
        assertThatThrownBy(()->internet.require(contract,"web_search")).isInstanceOf(SecurityException.class);
    }
    @Test void networkPermissionCannotBeExpandedByContract(){
        when(contract.authorization().networkPolicy()).thenReturn(CodingTaskContract.NetworkPolicy.ALLOWLIST);
        assertThatThrownBy(()->internet.require(contract,"web_search")).isInstanceOf(SecurityException.class);
    }
    @Test void providerOutageDoesNotPreventIndependentInternalAnalysis(){
        var scope=mock(KernelDataScope.class);when(scope.tenantId()).thenReturn("tenant");when(scope.userId()).thenReturn("user");
        when(models.providers()).thenThrow(new IllegalStateException("Provider control plane offline"));
        assertThat(internet.snapshot(scope,"agent","model-a",false)).isEmpty();
    }
}
