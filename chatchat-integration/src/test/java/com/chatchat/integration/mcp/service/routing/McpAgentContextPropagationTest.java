package com.chatchat.integration.mcp.service.routing;

import com.chatchat.common.tool.ToolInput;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class McpAgentContextPropagationTest {
    @Test void documentAclReceivesOnlyRuntimeOwnedAgentContext(){
        var bridge=mock(McpToolRegistryBridge.class,CALLS_REAL_METHODS);
        var args=new LinkedHashMap<String,Object>(Map.of("agentId","spoof","mcpContext",Map.of("authorizationAgentId","spoof","agentId","spoof","skillId","spoof")));
        var input=ToolInput.builder().context(Map.of("tenantId","tenant","userId","user","authorizationAgentId","authorized-agent")).build();
        ReflectionTestUtils.invokeMethod(bridge,"enrichInvocationContext",args,input);
        assertThat(args).containsEntry("agentId","authorized-agent");
        assertThat((Map<String,Object>)args.get("mcpContext")).containsEntry("authorizationAgentId","authorized-agent").doesNotContainKeys("agentId","skillId");
    }
    @Test void absentAgentCannotInheritAnAgentFromModelArguments(){
        var bridge=mock(McpToolRegistryBridge.class,CALLS_REAL_METHODS);
        var args=new LinkedHashMap<String,Object>(Map.of("agentId","spoof","mcpContext",Map.of("authorizationAgentId","spoof")));
        ReflectionTestUtils.invokeMethod(bridge,"enrichInvocationContext",args,ToolInput.builder().context(Map.of("tenantId","tenant","userId","user")).build());
        assertThat(args).doesNotContainKey("agentId");assertThat((Map<String,Object>)args.get("mcpContext")).doesNotContainKey("authorizationAgentId");
    }
}
