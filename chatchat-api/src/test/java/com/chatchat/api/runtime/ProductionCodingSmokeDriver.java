package com.chatchat.api.runtime;

import com.chatchat.api.ChatChatApplication;
import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.analysis.graph.ModelNativeAnalysisHarness;
import com.chatchat.agents.orchestration.model.MeteredChatModel;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import org.springframework.boot.SpringApplication;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Manual real-model acceptance; all example data and expected business answers remain test-only. */
public final class ProductionCodingSmokeDriver {
    public static void main(String[] args) throws Exception {
        try(var app=SpringApplication.run(ChatChatApplication.class,args)) {
            var resources=app.getBean(ModelResourceRegistry.class);
            var factory=app.getBean(ConfigurableChatModelFactory.class);
            var service=app.getBean(CodingAnalysisService.class);
            var collector=app.getBean(CodingEvidenceCollector.class);
            var identity=app.getBean(CodingInvocationContext.class);
            var coding=app.getBean(CodingAgentSPI.class);
            var spill=app.getBean(AnalysisEvidenceSpillStore.class);
            boolean text="text".equals(System.getenv("CODING_SMOKE_CASE"));
            String run="production-coding-smoke-"+UUID.randomUUID();
            var scope=GovernanceIsolationScope.runtime("coding-acceptance","coding-acceptance",run,run,run);
            var kernel=new KernelDataScope(scope.tenantId(),scope.userId(),scope.requestId(),scope.conversationId(),run,null,Map.of());
            var usage=new LinkedHashMap<String,Object>();usage.put("runId",run);
            var model=new MeteredChatModel(factory.create(resources.defaultChatModel(),Duration.ofSeconds(90),4096),
                usage,131072,0,0,0,0.8);
            List<Map<String,Object>> rows=text?List.of(Map.of("word","red"),Map.of("word","blue"),Map.of("word","red"),Map.of("word","green"),Map.of("word","red"))
                :List.of(Map.of("measurement","1"),Map.of("measurement","4"),Map.of("measurement","9"),Map.of("measurement","11"));
            String question=text?"Use RUN_CODING and Python to count occurrences of word red in the delivered source. "
                +"The Coding result must contain exactly one row with label string and count integer. Inspect the committed result and deliver the answer."
                :"Use RUN_CODING and Python to calculate the median of measurement in the delivered source with exact decimal arithmetic. "
                +"The Coding result must contain exactly one row with statistic as a decimal string. Inspect the committed result and deliver the answer.";
            var metadata=new LinkedHashMap<String,Object>();metadata.put("modelAnalysisProtocol","model_native_analysis.v2");
            var context=new AnalysisCoverageCoordinator.Request(model,question,null,
                Map.of("__agentDeadlineAt",System.currentTimeMillis()+300000),metadata,()->false,()->{},scope,null);
            System.out.println("PRODUCTION_CODING_SMOKE started runId="+run+" case="+(text?"text":"median"));
            var result=new ModelNativeAnalysisHarness(8).withCoding(service,context).execute(question,
                List.of(new Dataset("authorized-source",Map.of(),rows)),model,scope,spill,metadata,()->{},
                trace->{
                    if("MODEL_RETURNED".equals(trace.get("stage")))System.out.println("PRODUCTION_CODING_SMOKE parentTurn="+trace.get("turn"));
                    if("FAILED".equals(trace.get("eventState")))System.out.println("PRODUCTION_CODING_SMOKE rejected="+trace);
                });
            if(!(metadata.get("codingLastReceipt") instanceof Map<?,?> receipt))
                throw new IllegalStateException("The real main model did not invoke Coding: "+metadata.get("harnessTrace"));
            String task=String.valueOf(receipt.get("taskId"));
            if(!"COMPLETED".equals(receipt.get("status")) || !"INCONCLUSIVE".equals(receipt.get("validationStatus")))
                throw new IllegalStateException("Coding execution or validation scope is wrong: "+receipt);
            if(!Boolean.TRUE.equals(receipt.get("resultAvailable")) || !Boolean.FALSE.equals(receipt.get("reconciliationRequired"))
                    || !(receipt.get("evidenceRefs") instanceof List<?> refs) || refs.size()!=6)
                throw new IllegalStateException("Coding result admission or evidence contract is incomplete");
            if(!(metadata.get("harnessCodingTasks") instanceof List<?> tasks)
                    || tasks.stream().noneMatch(item->item instanceof Map<?,?> row && task.equals(row.get("taskId"))
                        && Boolean.TRUE.equals(row.get("resultAvailable")) && "NOT_EVALUATED".equals(row.get("businessValidation"))))
                throw new IllegalStateException("Runtime Coding task catalog did not retain result scope");
            var grants=EnumSet.allOf(CodingTaskAccess.Action.class);
            var outcome=identity.as(kernel,grants,()->coding.get(kernel,task));
            identity.as(kernel,grants,()->{
                try(var data=collector.openResult(kernel,task,String.valueOf(receipt.get("datasetReference")))) {
                    var actual=data.readPage(0,2).rows();
                    if(actual.size()!=1)throw new IllegalStateException("Unexpected acceptance row count");
                    if(text){
                        if(!"red".equals(actual.get(0).get("label")) || ((Number)actual.get(0).get("count")).longValue()!=3)
                            throw new IllegalStateException("Independent text acceptance failed");
                    }else if(new BigDecimal(String.valueOf(actual.get(0).get("statistic"))).compareTo(new BigDecimal("6.5"))!=0)
                        throw new IllegalStateException("Independent median acceptance failed");
                    return true;
                }
            });
            if(outcome.validationStatus()!=CodingTaskOutcome.ValidationStatus.INCONCLUSIVE
                    || result.datasetReferences().stream().noneMatch(ref->ref.equals(receipt.get("datasetReference"))))
                throw new IllegalStateException("Coding output was not admitted to the main analysis workspace");
            long before=outcome.revision();
            var repeated=identity.as(kernel,grants,()->coding.resume(kernel,task));
            if(repeated.revision()!=before)throw new IllegalStateException("Completed Coding task replayed work");
            System.out.println("PRODUCTION_CODING_SMOKE PASS taskId="+task+" parentCalls="+result.modelCalls()
                +" codingModelCalls="+outcome.usage().consumed().modelCalls()+" codeExecutions="+outcome.usage().consumed().codeExecutions()
                +" validationScope=STRUCTURAL_ONLY sixRefs="+((List<?>)receipt.get("evidenceRefs")).size()
                +" bundle="+outcome.evidenceBundleRef()+" meteredUsage="+usage);
        }
    }
}
