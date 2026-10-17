package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.datascience.sql.SqlGlotToolchain;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.security.InternalRequestSigner;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual child Node/Cline SDK, signed HTTP, Java/A2/H2 and Python SQLGlot; provider is deterministic. */
@EnabledIfSystemProperty(named="coding.nodeE2e",matches="true")
@EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
class CodingWorkspaceNodeIntegrationTest {
    @ParameterizedTest @ValueSource(strings={"mysql","python"})
    void sharedWorkerReadsContextRepairsSourceAndCommitsVersionedCandidatesWithoutExecution(String dialect) throws Exception {
        boolean pythonProfile="python".equals(dialect);
        String secret="fixture-only-coding-workspace-shared-secret";
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var source=new DriverManagerDataSource("jdbc:h2:mem:node-workspace-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(source),json,new DataSourceTransactionManager(source));
        var identity=new CodingInvocationContext();var receipts=new ClineModelReceiptStore(evidence);
        var workspaceProof=new CodingWorkspaceProofVerifier(evidence);
        var timer=Executors.newSingleThreadScheduledExecutor();
        var callback=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var callbackThreads=Executors.newCachedThreadPool();callback.setExecutor(callbackThreads);
        Process node=null;
        try {
            var tasks=new DurableCodingTaskService(evidence,json,Clock.systemUTC(),identity,(scope,ref)->{
                String id=ref.substring("runtime-evidence:".length());
                return evidence.find(scope,id).isPresent() || evidence.snapshot(scope,id).isPresent();
            },(scope,task,op,handle,ref)->receipts.confirmsStopped(scope,task,op,handle,ref)
                || workspaceProof.confirmsStopped(scope,task,op,handle,ref),Runnable::run,new ConcurrentTaskScheduler(timer));
            var beans=new DefaultListableBeanFactory();
            var workspace=new CodingWorkspaceService(tasks,identity,evidence,beans.getBeanProvider(CodingAgentSPI.class),
                new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),json,60);
            var provider=mock(ClineRuntimeModelBridge.class);var turns=new AtomicInteger();
            when(provider.complete(any(),any())).thenAnswer(call->{
                int turn=turns.incrementAndGet();JsonNode request=call.getArgument(1);
                List<Map<String,Object>> tools;
                if(turn==1)tools=List.of(Map.of("id","read-context","name","workspace_read","input",Map.of()));
                else if(turn<=3){
                    if(turn==2)assertThat(request.toString()).contains("authorized Mapping","historical SQL");
                    if(turn==3)assertThat(request.toString()).contains("FAILED");
                    tools=List.of(Map.of("id","proposal-"+turn,"name","workspace_propose","input",Map.of("files",List.of(
                        Map.of("path",pythonProfile?"src/a.py":"sql/a.sql","content",pythonProfile
                            ?turn==2?"def broken(":"# preserve original comment\nprint(1)":turn==2?"SELECT * FROM":"-- preserve original comment\nSELECT 1;"),
                        Map.of("path",pythonProfile?"src/b.py":"etl/b.sql","content",pythonProfile?"def helper():\n    return 2\n":"SELECT 2;")),"notes","Candidate source only; no code executed")));
                }else tools=List.of();
                return Map.of("text",turn>3?"Review the parsed candidates.":"","toolCalls",tools,
                    "usage",Map.of("inputTokens",10,"outputTokens",10));
            });
            var controller=new ClineModelCallbackController(identity,tasks,provider,receipts,
                mock(CodingInputSnapshotService.class),mock(CodingValidationPlanService.class),mock(CodingEvidenceCollector.class),json);
            controller.setWorkspace(workspace);
            var callbacks=new AtomicInteger();
            callback.createContext("/internal/coding/cline/",exchange->{
                int status=200;Object reply;
                try{
                    byte[] bytes=exchange.getRequestBody().readAllBytes();var headers=exchange.getRequestHeaders();
                    String expected=InternalRequestSigner.signBody(secret,"POST",exchange.getRequestURI().getPath(),
                        headers.getFirst(InternalRequestSigner.TIMESTAMP_HEADER),headers.getFirst(InternalRequestSigner.NONCE_HEADER),bytes);
                    if(!InternalRequestSigner.matches(headers.getFirst("Authorization"),"Bearer "+secret)
                            || !InternalRequestSigner.matches(expected,headers.getFirst(InternalRequestSigner.SIGNATURE_HEADER)))
                        throw new SecurityException("Callback signature mismatch");
                    callbacks.incrementAndGet();JsonNode body=json.readTree(bytes);
                    String action=exchange.getRequestURI().getPath().substring("/internal/coding/cline/".length());
                    reply=switch(action){
                        case "reserve-model"->controller.reserve(body);case "model"->controller.model(body);
                        case "settle-model"->controller.settle(body);case "event"->controller.event(body);
                        case "checkpoint"->controller.checkpoint(body);
                        case "finish"->controller.finish(body);case "workspace-read"->controller.readWorkspace(body);
                        case "workspace-propose"->controller.proposeWorkspace(body);
                        default->throw new IllegalArgumentException("Unexpected callback "+action);
                    };
                }catch(ResponseStatusException failure){status=failure.getStatusCode().value();reply=Map.of("message",failure.getReason());}
                catch(Exception failure){status=500;reply=Map.of("message",failure.toString());}
                byte[] bytes=json.writeValueAsBytes(reply);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
                exchange.close();
            });
            callback.start();int port;try(var listener=new java.net.ServerSocket(0)){port=listener.getLocalPort();}
            String module=Path.of("..","coding-agent-node","src","server.js").toAbsolutePath().normalize().toUri().toString();
            String script="import {createCodingServer} from '"+module+"';createCodingServer({secret:'"+secret
                +"',callbackUrl:'http://127.0.0.1:"+callback.getAddress().getPort()+"'}).listen("+port+",'127.0.0.1');";
            node=new ProcessBuilder(System.getProperty("coding.node","node"),"--input-type=module","-e",script)
                .redirectErrorStream(true).start();
            var http=HttpClient.newHttpClient();long startup=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean ready=false;
            while(System.nanoTime()<startup){
                try{ready=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/healthz"))
                    .header("Authorization","Bearer "+secret).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                catch(Exception waiting){if(!node.isAlive())break;}
                if(ready)break;Thread.sleep(25);
            }
            assertThat(ready).as(workerLog(node)).isTrue();
            var python=mock(CodingPythonExecutionBridge.class);
            var adapter=new ClineCodingAdapter(tasks,python,mock(CodingInputSnapshotService.class),mock(CodingValidationPlanService.class),
                mock(CodingEvidenceCollector.class),receipts,URI.create("http://127.0.0.1:"+port),secret,json);
            adapter.setWorkspace(workspace);beans.registerSingleton("codingAgent",adapter);
            var scope=new KernelDataScope("tenant","owner",null,null,"node-run",null,Map.of());
            var outcome=workspace.submit(scope,"node-task","Use authorized Mapping and historical SQL to produce a two-file source project.",dialect,"");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!outcome.status().terminal() && System.nanoTime()<deadline){Thread.sleep(50);outcome=workspace.get(scope,"node-task");}
            assertThat(outcome.status()).as("%s %s; worker: %s",outcome.reasonCode(),outcome.summary(),workerLog(node))
                .isEqualTo(CodingTaskOutcome.Status.COMPLETED);
            assertThat(outcome.schemaVersion()).isEqualTo(CodingProtocol.WORKSPACE_VERSION);
            assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(4);
            assertThat(outcome.usage().consumed().toolCalls()).isEqualTo(3);
            assertThat(outcome.usage().consumed().codeExecutions()).isZero();
            assertThat(outcome.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
            assertThat((List<?>)workspace.output(scope,"node-task",outcome).get("files")).hasSize(2);
            assertThat(callbacks.get()).isGreaterThan(10);verifyNoInteractions(python);
        }finally{
            if(node!=null){
                node.descendants().forEach(ProcessHandle::destroyForcibly);
                node.destroyForcibly();node.waitFor(5,TimeUnit.SECONDS);
                node.getInputStream().close();node.getErrorStream().close();node.getOutputStream().close();
            }
            callback.stop(0);callbackThreads.shutdownNow();timer.shutdownNow();
        }
    }
    private static String workerLog(Process worker) throws java.io.IOException {
        var stream=worker.getInputStream();int available=stream.available();
        return available==0?"":new String(stream.readNBytes(Math.min(available,8192)),java.nio.charset.StandardCharsets.UTF_8);
    }
}
