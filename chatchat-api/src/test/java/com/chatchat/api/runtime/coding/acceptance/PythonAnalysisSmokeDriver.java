package com.chatchat.api.runtime.coding.acceptance;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.analysis.graph.ModelNativeAnalysisHarness;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.api.ChatChatApplication;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingAgentSPI;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;

/** Manual staging gate: real model, real Node SDK, real Python; fixture values stay in execution. */
public final class PythonAnalysisSmokeDriver {
    public static void main(String[] args) throws Exception {
        try(var app=SpringApplication.run(ChatChatApplication.class,args)) {
            var resources=app.getBean(ModelResourceRegistry.class);
            var delegate=app.getBean(ConfigurableChatModelFactory.class)
                .create(resources.defaultChatModel(),Duration.ofSeconds(90),8192);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            ChatModel model=new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    String prompt=request.messages().toString();
                    if(prompt.contains("PRIVATE_FIXTURE_ROW_"))
                        throw new IllegalStateException("Raw source rows entered parent model context");
                    calls.incrementAndGet();return delegate.chat(request);
                }
            };
            String run="python-analysis-"+UUID.randomUUID();
            var scope=GovernanceIsolationScope.runtime("coding-acceptance","coding-acceptance",run,run,run);
            var metadata=new LinkedHashMap<String,Object>();metadata.put("analysisExecutionPolicy","PYTHON_REQUIRED");
            var trace=new ArrayList<Map<String,Object>>();
            String question="Compute the exact median of measurement in the delivered dataset. Use Python and return a result "
                +"with statistic as a decimal string. This is a recovery acceptance drill: delegate an instruction to first "
                +"execute a diagnostic program that raises ValueError('acceptance_repair_probe'), read its archived stderr, "
                +"then repair it with a complete program that reads the authorized input and writes rows.jsonl. "
                +"Do not include source rows or labels in the final answer. Finish with the computed statistic and evidence references.";
            var context=new AnalysisCoverageCoordinator.Request(model,question,null,
                Map.of("__agentDeadlineAt",System.currentTimeMillis()+600000),metadata,()->false,()->{},scope,null);
            var rows=List.<Map<String,Object>>of(Map.of("measurement","1","label","PRIVATE_FIXTURE_ROW_A"),
                Map.of("measurement","4","label","PRIVATE_FIXTURE_ROW_B"),Map.of("measurement","9","label","PRIVATE_FIXTURE_ROW_C"),
                Map.of("measurement","11","label","PRIVATE_FIXTURE_ROW_D"));
            var result=new ModelNativeAnalysisHarness(8).withCoding(app.getBean(CodingAnalysisService.class),context)
                .execute(question,List.of(new Dataset("authorized-source",Map.of(),rows)),model,scope,
                    AnalysisEvidenceSpillStore.disabled(),metadata,()->{},event->{
                        trace.add(event);
                        if("CODING_TRACE".equals(event.get("eventKind")))
                            System.out.println("PYTHON_ANALYSIS_TRACE task="+event.get("taskId")+" sequence="+event.get("sequence")+" type="+event.get("codingEventType"));
                    });
            var tasks=(List<?>)metadata.getOrDefault("harnessCodingTasks",List.of());
            var task=tasks.stream().filter(item->item instanceof Map<?,?> map && Boolean.TRUE.equals(map.get("resultAvailable")))
                .map(item->(Map<?,?>)item)
                .filter(item->result.markdown().contains(String.valueOf(item.get("datasetReference"))))
                .reduce((earlier,later)->later).orElseThrow(()->new IllegalStateException("No report-bound Python result"));
            String taskId=String.valueOf(task.get("taskId"));
            var kernel=new KernelDataScope(scope.tenantId(),scope.userId(),scope.requestId(),scope.conversationId(),run,null,Map.of());
            var outcome=app.getBean(CodingInvocationContext.class).as(kernel,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
                var completed=app.getBean(CodingAgentSPI.class).get(kernel,taskId);
                if(trace.stream().filter(event->"CODING_TRACE".equals(event.get("eventKind")))
                    .map(event->String.valueOf(event.get("stepId"))).filter(id->id.matches("cline-[0-9a-f]{64}"))
                    .distinct().count()<2)throw new IllegalStateException("Repair branch was not exercised");
                try(var data=app.getBean(CodingEvidenceCollector.class).openResult(kernel,taskId,completed.resultDatasetRef())) {
                    var computed=data.readPage(0,2).rows();
                    if(computed.size()!=1 || computed.get(0).get("statistic")==null
                        || new BigDecimal("6.5").compareTo(new BigDecimal(computed.get(0).get("statistic").toString()))!=0)
                        throw new IllegalStateException("Independent median check failed");
                }
                return completed;
            });
            if(!"REQUESTED".equals(metadata.get("publicationState")))throw new IllegalStateException("Report was not completed");
            String output=System.getenv("PYTHON_ANALYSIS_EVIDENCE_FILE");
            if(output!=null) {
                var directory=java.nio.file.Path.of(output).toAbsolutePath().getParent().resolve("operations");
                java.nio.file.Files.createDirectories(directory);
                var artifacts=new ArrayList<Map<String,Object>>();
                boolean probeObserved=false;
                var operations=trace.stream().filter(event->"CODING_TRACE".equals(event.get("eventKind")))
                    .filter(event->String.valueOf(event.get("stepId")).matches("cline-[0-9a-f]{64}"))
                    .map(event->Map.of("taskId",String.valueOf(event.get("taskId")),"operationId",String.valueOf(event.get("stepId"))))
                    .distinct().toList();
                for(var operationBinding:operations) for(String kind:List.of("code","stdout","stderr")) {
                    String operation=operationBinding.get("operationId"),operationTask=operationBinding.get("taskId");
                    String name="code".equals(kind)?"main.py":kind+".txt";
                    byte[] bytes=app.getBean(com.chatchat.common.mcp.python.McpPythonControlPlanePort.class)
                        .artifact(scope.tenantId(),scope.userId(),run,operationTask,operation,"code".equals(kind)?"code":"logs",name);
                    java.nio.file.Files.write(directory.resolve(operation+"-"+name),bytes);
                    if("stderr".equals(kind) && new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("acceptance_repair_probe"))
                        probeObserved=true;
                    artifacts.add(Map.of("taskId",operationTask,"operationId",operation,"kind",kind,"bytes",bytes.length,"sha256",
                        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
                }
                if(!probeObserved)throw new IllegalStateException("Archived stderr did not prove the requested repair probe");
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValue(new java.io.File(output),Map.of("runId",run,"taskId",taskId,"totalModelCalls",calls.get(),
                        "trace",trace,"report",result.markdown(),"usage",outcome.usage(),"independentValidation","PASSED",
                        "rawSourceMarkerAudit","PASSED","artifacts",artifacts));
            }
            System.out.println("PYTHON_ANALYSIS_SMOKE PASS run="+run+" task="+taskId+" totalModelCalls="+calls.get());
        }
    }
}
