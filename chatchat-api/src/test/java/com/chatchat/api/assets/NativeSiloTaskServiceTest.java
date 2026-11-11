package com.chatchat.api.assets;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeSiloTaskService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real SQL contract/owner fencing with a versioned in-memory blob transport.
 * These tests do not claim model, real Silo or application HTTP acceptance.
 */
class NativeSiloTaskServiceTest {
    SiloCodingWorkspaceServiceTest fixture;
    NativeSiloTaskService service;
    final CodingInvocationContext identity=new CodingInvocationContext();
    String original;
    final String objective="Explore the original input and choose meaningful source filenames.";
    @BeforeEach void setup(){
        fixture=new SiloCodingWorkspaceServiceTest();fixture.setup();
        var json=new ObjectMapper().findAndRegisterModules();
        service=new NativeSiloTaskService(identity,fixture.service,
            new JdbcRuntimeEvidenceStore(fixture.jdbc,json,new DataSourceTransactionManager(fixture.jdbc.getDataSource())),json);
        original=fixture.assets.upload(fixture.owner,"original-columns.csv","value\n42\n".getBytes(StandardCharsets.UTF_8)).id();
    }
    CodingTaskContract.Budget budget(long calls){return new CodingTaskContract.Budget("native-budget",Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS),
        calls,-1,-1,-1,"unlimited","USD",-1,3L*1024*1024*1024,64L*1024*1024);}
    <T>T authorized(Supplier<T> operation){return identity.as(fixture.scope,EnumSet.of(CodingTaskAccess.Action.READ,CodingTaskAccess.Action.SUBMIT),operation);}
    CodingTaskContract capture(){return service.capture(fixture.scope,"native-task",List.of(original),objective,"fixed-model",budget(-1));}

    @Test void inputCaptureCannotSelfGrantAnUnauthenticatedRuntimeActor(){
        long quota=fixture.assets.space(fixture.owner).usedBytes();
        assertThatThrownBy(this::capture).isInstanceOf(CodingContractException.class);
        assertThat(fixture.assets.space(fixture.owner).usedBytes()).isEqualTo(quota);
    }
    @Test void preservesPhysicalBytesAndDoesNotRequireASandboxOrAFixedSourceFilename(){
        authorized(()->{
            var contract=capture();assertThat(NativeSiloTaskService.supports(contract)).isTrue();
            assertThat(contract.objective().instruction()).isEqualTo(objective);
            assertThat(contract.outputContract().requiredFiles()).containsExactly("delivery_manifest.json");
            assertThat(contract.authorization().allowedTools()).contains("run_commands","editor","read_files").doesNotContain("python_execute");
            assertThat(contract.authorization().allowedSideEffects()).contains(CodingTaskContract.SideEffect.CODE_EXECUTION);
            assertThat(contract.authorization().networkPolicy()).isEqualTo(CodingTaskContract.NetworkPolicy.NONE);
            var state=fixture.service.get(fixture.scope,contract.taskId());
            assertThat(fixture.assets.readTask(fixture.scope,contract.taskId(),state.inputs().get(0).assetId())).isEqualTo("value\n42\n".getBytes(StandardCharsets.UTF_8));
            assertThat(service.requireAuthorized(contract)).containsEntry("selectedModel","fixed-model").doesNotContainKey("authorizedCustomer");
            return null;
        });
    }
    @Test void domainReportProfilesAreNotBuiltIntoTheGenericRuntime(){
        authorized(()->{
            assertThatThrownBy(()->service.capture(fixture.scope,"native-task",List.of(original),objective,"fixed-model",budget(-1),
                Map.of("profile","customer-account-v1"),List.of("REPORT")))
                .hasMessageContaining("generic artifact integrity");
            return null;
        });
    }
    @Test void repeatingARequestRetainsItsOriginalContractDeadlineAndInputVersions(){
        authorized(()->{
            var first=capture();var repeated=capture();assertThat(repeated).isEqualTo(first);
            assertThatThrownBy(()->service.capture(fixture.scope,"native-task",List.of(original),"Another objective","fixed-model",budget(-1)))
                .hasMessageContaining("conflict");
            return null;
        });
    }
    @Test void finiteExecutionCountsAreRejectedInsteadOfSilentlyRestoringOldBudgets(){
        authorized(()->{
            assertThatThrownBy(()->service.capture(fixture.scope,"native-task",List.of(original),objective,"fixed-model",budget(8)))
                .hasMessageContaining("unlimited");return null;
        });
    }
    @Test void anotherOwnerCannotCaptureTheOriginalAssetOrReadItsTaskContract(){
        var contract=authorized(this::capture);
        var foreign=new KernelDataScope("tenant-b","user-b","request",null,"run-b",null,Map.of());
        identity.as(foreign,EnumSet.of(CodingTaskAccess.Action.READ,CodingTaskAccess.Action.SUBMIT),()->{
            assertThatThrownBy(()->service.capture(foreign,"other-task",List.of(original),objective,"fixed-model",budget(-1))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->service.requireAuthorized(contract)).isInstanceOf(CodingContractException.class);return null;
        });
    }
    @Test void cancellationClosesNewInputCapabilitiesWithoutChangingTheSnapshot(){
        authorized(()->{
            var contract=capture();var state=fixture.service.get(fixture.scope,contract.taskId());
            fixture.service.cancel(fixture.scope,contract.taskId(),state.epoch());
            assertThatThrownBy(()->service.inputCapabilities(contract)).hasMessageContaining("fence");
            assertThat(fixture.service.get(fixture.scope,contract.taskId()).snapshotVersion()).isEqualTo(state.snapshotVersion());return null;
        });
    }
    @Test void existingSpiRejectsAnUnnegotiatedNodeWithoutCallingSandboxOrAcceptingATask()throws Exception{
        var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/input-capabilities",exchange->{
            exchange.getRequestBody().readAllBytes();byte[] reply="{\"workspaceProfiles\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,reply.length);try(var out=exchange.getResponseBody()){out.write(reply);}exchange.close();
        });http.start();
        try{
            var tasks=org.mockito.Mockito.mock(com.chatchat.agents.runtime.coding.DurableCodingTaskService.class);
            var python=org.mockito.Mockito.mock(com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge.class);
            var inputs=org.mockito.Mockito.mock(com.chatchat.api.runtime.coding.data.CodingInputSnapshotService.class);
            var plans=org.mockito.Mockito.mock(com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService.class);
            var collector=org.mockito.Mockito.mock(com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector.class);
            var adapter=new com.chatchat.api.runtime.coding.cline.ClineCodingAdapter(tasks,python,inputs,plans,collector,null,
                java.net.URI.create("http://127.0.0.1:"+http.getAddress().getPort()),"0".repeat(32),new ObjectMapper().findAndRegisterModules());
            adapter.setNativeSessions(true);adapter.setNativeSilo(service);
            adapter.setLicenseEntitlements(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort(){
                public CodingDispatchEntitlement codingDispatchEntitlement(){return new CodingDispatchEntitlement(true,"VALID");}
            });
            authorized(()->{var contract=capture();assertThatThrownBy(()->adapter.submit(contract)).hasMessageContaining("not been negotiated");return null;});
            org.mockito.Mockito.verifyNoInteractions(tasks,python,inputs,plans,collector);
        }finally{http.stop(0);}
    }
}
