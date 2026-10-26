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
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"mysql,false","mysql,true","python,false","python,true"})
    void sharedWorkerReadsContextRepairsSourceAndCommitsVersionedCandidatesWithoutExecution(String dialect,boolean fast) throws Exception {
        exercise(dialect,fast,false);
    }
    /** Opt-in only: credentials remain in a private external file, never test reports. */
    @EnabledIfSystemProperty(named="coding.realModelConfig",matches=".+")
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"mysql,false","mysql,true","python,false","python,true"})
    void authorizedRealModelComparison(String dialect,boolean fast) throws Exception {
        exercise(dialect,fast,true);
    }
    private void exercise(String dialect,boolean fast,boolean realProvider) throws Exception {
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
                new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),json,realProvider?300:60);
            workspace.setFastPathEnabled(fast);
            var turns=new AtomicInteger();
            var telemetry=new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>();
            ClineRuntimeModelBridge provider;
            if(realProvider) {
                var config=json.readTree(Files.readString(Path.of(System.getProperty("coding.realModelConfig"))));
                assertThat(config.path("name").asText()).isEqualTo(System.getProperty("coding.allowedModel"));
                assertThat(config.path("enabled").asBoolean()).isTrue();
                var models=new com.chatchat.common.config.ModelsConfig();
                models.setDefaultChatModel(config.path("name").asText());
                var connection=new com.chatchat.common.config.ModelsConfig.ModelConnectionConfig();
                connection.setBaseUrl(config.path("base_url").asText());
                connection.setModelName(config.path("provider_model").asText());
                connection.setProtocol(config.path("protocol").asText());
                connection.setApiKey(com.chatchat.common.security.InternalSecretCipher.decryptIfNecessary(
                    config.path("api_key_cipher").asText(),config.path("cryptoKey").asText()));
                connection.setMaxRetries(0);connection.setTimeout(config.path("timeout").asInt());
                connection.setMaxTokens(config.path("max_tokens").asInt());
                models.getChatModels().put(config.path("name").asText(),connection);
                var resources=new com.chatchat.common.config.ModelResourceRegistry(models);
                provider=spy(new ClineRuntimeModelBridge(resources,new com.chatchat.agents.model.ConfigurableChatModelFactory(resources,json),json));
                provider.setWorkspace(workspace);provider.setRuntimeGuard(tasks,identity);
                doAnswer(call->{
                    turns.incrementAndGet();
                    @SuppressWarnings("unchecked") var answer=(Map<String,Object>)call.callRealMethod();
                    var measured=new LinkedHashMap<String,Object>();
                    measured.put("modelInfo",answer.get("modelInfo"));measured.put("usage",answer.get("usage"));
                    telemetry.add(measured);return answer;
                }).when(provider).complete(any(),any());
            } else {
            provider=mock(ClineRuntimeModelBridge.class);
            when(provider.complete(any(),any())).thenAnswer(call->{
                int turn=turns.incrementAndGet()+(fast?1:0);JsonNode request=call.getArgument(1);
                List<Map<String,Object>> tools;
                if(turn==1)tools=List.of(Map.of("id","read-context","name","workspace_read","input",Map.of()));
                else if(turn<=3){
                    if(turn==2)assertThat(request.toString()).contains("authorized Mapping","historical SQL");
                    if(turn==3)assertThat(request.toString()).contains("FAILED");
                    var proposedFiles=List.of(
                        Map.of("path",pythonProfile?"src/a.py":"sql/a.sql","content",pythonProfile
                            ?turn==2?"def broken(":"# preserve original comment\nprint(1)":turn==2?"SELECT * FROM":"-- preserve original comment\nSELECT 1;"),
                        Map.of("path",pythonProfile?"src/b.py":"etl/b.sql","content",pythonProfile?"def helper():\n    return 2\n":"SELECT 2;"));
                    List<?> files=proposedFiles;
                    if(fast && turn==3) {
                        String oldSource=pythonProfile?"def broken(":"SELECT * FROM";
                        String fixedSource=pythonProfile?"# preserve original comment\nprint(1)":"-- preserve original comment\nSELECT 1;";
                        files=List.of(Map.of("path",pythonProfile?"src/a.py":"sql/a.sql","repair",Map.of(
                            "baseCodeSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(oldSource.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                            "edits",List.of(Map.of("oldText",oldSource,"newText",fixedSource)))));
                    }
                    tools=List.of(Map.of("id","proposal-"+turn,"name","workspace_propose","input",Map.of("files",files,"notes","Candidate source only; no code executed")));
                }else tools=List.of();
                return Map.of("text",turn>3?"Review the parsed candidates.":"","toolCalls",tools,
                    "usage",Map.of("inputTokens",10,"outputTokens",10));
            });
            }
            var controller=new ClineModelCallbackController(identity,tasks,provider,receipts,
                mock(CodingInputSnapshotService.class),mock(CodingValidationPlanService.class),mock(CodingEvidenceCollector.class),json);
            controller.setWorkspace(workspace);
            var callbacks=new AtomicInteger();
            var callbackErrors=new java.util.concurrent.ConcurrentLinkedQueue<String>();
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
                }catch(ResponseStatusException failure){callbackErrors.add(exchange.getRequestURI().getPath()+": "+failure.getReason());status=failure.getStatusCode().value();reply=Map.of("message",failure.getReason());}
                catch(Exception failure){callbackErrors.add(failure.toString());status=500;reply=Map.of("message",failure.toString());}
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
            adapter.setLicenseEntitlements(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort() {
                @Override public CodingDispatchEntitlement codingDispatchEntitlement() {
                    return new CodingDispatchEntitlement(true, "VALID");
                }
            });
            var scope=new KernelDataScope("tenant","owner",null,null,"node-run",null,Map.of());
            String workload=realProvider?(pythonProfile
                ?"Generate exactly src/a.py and src/b.py as complete source candidates. In src/a.py implement total(values), returning the sum of the supplied list. In src/b.py implement average(values), returning None for an empty list and otherwise its arithmetic mean. Functions must be pure: no imports, I/O, network, shell, execution, dependencies or files at import. Preserve the comment '# candidate for review' in both files. Do not create tests or extra files. Return concise notes."
                :"Generate exactly sql/a.sql and sql/b.sql as complete source candidates. sql/a.sql must contain SELECT SUM(value) AS total FROM input_values;. sql/b.sql must contain SELECT AVG(value) AS average FROM input_values;. Preserve the comment '-- candidate for review' in both files. No DDL, DML, procedures, source connections or extra files. Return concise notes.")
                :"Use authorized Mapping and historical SQL to produce a two-file source project.";
            long started=System.nanoTime();
            var outcome=workspace.submit(scope,"node-task",workload,dialect,"");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(realProvider?300:30);
            while(!outcome.status().terminal() && System.nanoTime()<deadline){Thread.sleep(50);outcome=workspace.get(scope,"node-task");}
            if(realProvider) {
                var report=new LinkedHashMap<String,Object>();
                report.put("scope","REAL_PROVIDER_ISOLATED_JAVA_NODE_H2_PARSER");report.put("productionModified",false);
                report.put("model",System.getProperty("coding.allowedModel"));report.put("language",dialect);report.put("fastPath",fast);
                report.put("workloadSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(workload.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                report.put("wallMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                report.put("status",outcome.status().name());report.put("usage",outcome.usage());report.put("modelCalls",telemetry);
                report.put("cost",null);report.put("generatedCodeExecuted",false);
                report.put("events",workspace.events(scope,"node-task"));
                if(outcome.status()==CodingTaskOutcome.Status.COMPLETED)report.put("output",workspace.output(scope,"node-task",outcome));
                var directory=Path.of(System.getProperty("coding.benchmarkOutput","target/codegen-real-benchmark"));Files.createDirectories(directory);
                Files.writeString(directory.resolve(dialect+"-"+(fast?"fast":"full")+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
            assertThat(outcome.status()).as("%s %s; model turns: %s; callback errors: %s; worker: %s",outcome.reasonCode(),outcome.summary(),turns.get(),callbackErrors,workerLog(node))
                .isEqualTo(CodingTaskOutcome.Status.COMPLETED);
            assertThat(outcome.schemaVersion()).isEqualTo(CodingProtocol.WORKSPACE_VERSION);
            if(!realProvider) {
                assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(fast?2:4);
                assertThat(outcome.usage().consumed().toolCalls()).isEqualTo(3);
            }
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
