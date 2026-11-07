package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Receipt trust-boundary unit tests. Stop plane is mocked; not model or Sandbox acceptance. */
class NativeCommandPolicyReceiptTest {
    final ObjectMapper json=new ObjectMapper();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final String operation="cline-"+"e".repeat(64),declared="python -m unittest discover -s tests -v";
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final NativeCodingWorkspaceService workspace=mock(NativeCodingWorkspaceService.class);
    final CodingPythonExecutionBridge bridge=mock(CodingPythonExecutionBridge.class);
    final McpPythonControlPlanePort python=mock(McpPythonControlPlanePort.class);
    final McpPythonControlPlanePort.OperationView stopped=mock(McpPythonControlPlanePort.OperationView.class);
    final CodingTaskContract c=mock(CodingTaskContract.class);
    NativeCodingCommandService commands;JsonNode prepared;
    @BeforeEach void setup()throws Exception{
        when(c.scope()).thenReturn(scope);when(c.taskId()).thenReturn("task");
        when(c.metadataRefs()).thenReturn(List.of(NativeExecutorPolicy.PIPEFAIL.profile()));
        when(c.runtimeContext()).thenReturn(Map.of("nativeExecutionPolicy",NativeExecutorPolicy.PIPEFAIL.id(),"pythonEnvironment",Map.of("imageDigest","sha256:"+"f".repeat(64))));
        var budget=mock(CodingTaskContract.Budget.class);when(budget.maxOutputBytes()).thenReturn(1048576L);when(c.budget()).thenReturn(budget);
        when(tasks.verifiedOperation(scope,"task",operation)).thenReturn(Optional.of(new DurableCodingTaskService.VerifiedOperation(operation,1,"handle","proof:stopped")));
        when(bridge.get(scope,"task",operation)).thenReturn(stopped);when(stopped.executorStopped()).thenReturn(true);when(stopped.exitCode()).thenReturn(0);
        when(stopped.codeHash()).thenReturn("a".repeat(64));when(stopped.imageDigest()).thenReturn("sha256:"+"f".repeat(64));
        when(workspace.requireAuthorized(c)).thenReturn(Map.of("acceptanceCommand",declared));
        var data=new LinkedHashMap<String,Object>();data.put("sourceHash","a".repeat(64));data.put("manifestHash","d".repeat(64));
        data.put("commandHash","c".repeat(64));data.put("intentHash","b".repeat(64));data.put("commandOrdinal",1);
        data.put("executorPolicy",NativeExecutorPolicy.PIPEFAIL.id());data.put("executorHelperSha256",NativeExecutorPolicy.PIPEFAIL.helperSha256());
        data.put("executorBaseHelperSha256",NativeExecutorPolicy.LITERAL.helperSha256());data.put("verificationEligible",false);
        data.put("verificationDeclarationSha256",NativeCommandVerification.declarationHash(declared));prepared=json.valueToTree(data);
        var raw=new LinkedHashMap<>(data);raw.remove("sourceHash");raw.put("exitCode",0);raw.put("sdkVersion","0.0.93");raw.put("stdout","workload output");raw.put("stderr","");
        raw.put("verificationPolicy",NativeExecutorPolicy.PIPEFAIL.id());raw.put("declaredCommandMatched",true);raw.put("declaredCommandSha256","0".repeat(64));
        when(python.artifact(eq("tenant"),eq("owner"),eq("run"),eq("task"),eq(operation),eq("results"),eq("native-command.json"))).thenReturn(json.writeValueAsBytes(raw));
        commands=new NativeCodingCommandService(tasks,workspace,bridge,python,json);
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void workloadFlagsCannotReplaceHostPreparedEligibility(boolean eligible){
        ((com.fasterxml.jackson.databind.node.ObjectNode)prepared).put("verificationEligible",eligible);
        var result=commands.receipt(c,null,operation,prepared,false);
        assertThat(result).containsEntry("declaredCommandMatched",eligible).containsEntry("declaredCommandSha256",NativeCommandVerification.declarationHash(declared));
        assertThat(result).containsEntry("sourceHash","a".repeat(64)).containsEntry("stopProofRef","proof:stopped");
    }
    @Test void workloadZeroExitCannotReplaceStoppedProcessFailure(){
        when(stopped.exitCode()).thenReturn(1);
        assertThatThrownBy(()->commands.receipt(c,null,operation,prepared,false)).hasMessageContaining("differs from stopped process");
    }
    @Test void anotherSourceVersionCannotCertifyTheFrozenCommand(){
        when(stopped.codeHash()).thenReturn("0".repeat(64));
        assertThatThrownBy(()->commands.receipt(c,null,operation,prepared,false)).hasMessageContaining("binding differs");
        verifyNoInteractions(python);
    }
    @Test void missingIndependentStopNeverReadsWorkloadClaims(){
        when(tasks.verifiedOperation(scope,"task",operation)).thenReturn(Optional.empty());
        assertThatThrownBy(()->commands.receipt(c,null,operation,prepared,false)).hasMessageContaining("stop proof is absent");
        verifyNoInteractions(python,bridge);
    }
}
