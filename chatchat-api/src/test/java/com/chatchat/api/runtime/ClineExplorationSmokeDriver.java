package com.chatchat.api.runtime;

import com.chatchat.api.ChatChatApplication;
import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.model.MeteredChatModel;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import org.springframework.boot.SpringApplication;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Isolated real-model callback drill; fixture calculations never enter production Runtime code. */
public final class ClineExplorationSmokeDriver {
    public static void main(String[] args) throws Exception {
        try(var app=SpringApplication.run(ChatChatApplication.class,args)){
            var resources=app.getBean(ModelResourceRegistry.class);
            var factory=app.getBean(ConfigurableChatModelFactory.class);
            var service=app.getBean(CodingAnalysisService.class);
            var collector=app.getBean(CodingEvidenceCollector.class);
            var identity=app.getBean(CodingInvocationContext.class);
            var coding=app.getBean(CodingAgentSPI.class);
            String run="production-coding-smoke-"+UUID.randomUUID();
            var scope=GovernanceIsolationScope.runtime("coding-acceptance","coding-acceptance",run,run,run);
            var kernel=new KernelDataScope(scope.tenantId(),scope.userId(),scope.requestId(),scope.conversationId(),run,null,Map.of());
            var usage=new LinkedHashMap<String,Object>();usage.put("runId",run);
            var model=new MeteredChatModel(factory.create(resources.defaultChatModel(),Duration.ofSeconds(90),4096),usage,131072,0,0,0,0.8);
            var rows=List.<Map<String,Object>>of(Map.of("measurement","1"),Map.of("measurement","4"),Map.of("measurement","9"),Map.of("measurement","11"));
            var sources=new LinkedHashMap<String,Dataset>();sources.put("authorized-source",new Dataset("authorized-source",Map.of(),rows));
            String instruction="Integration diagnostic drill. First execute exactly one Python program that checks the authorized input file "
                +"and prints a diagnostic WITHOUT creating rows.jsonl. This deliberately produces no structured output. "
                +"Read the Runtime outputStatus REQUIRED_OUTPUT_MISSING; it is expected and is not an authentication failure. "
                +"Then execute a fresh complete Python program that reads the original JSONL input, calculates the median of measurement "
                +"using exact Decimal arithmetic, and writes exactly one result row with statistic as a decimal string to rows.jsonl. "
                +"Do not repeat diagnostic calls or rely on previous files. Finish only after Runtime has archived the structured result.";
            var metadata=new LinkedHashMap<String,Object>();
            var context=new AnalysisCoverageCoordinator.Request(model,instruction,null,
                Map.of("__agentDeadlineAt",System.currentTimeMillis()+300000),metadata,()->false,()->{},scope,null);
            System.out.println("PRODUCTION_CODING_SMOKE started runId="+run+" case=exploration-direct");
            var receipt=service.execute(context,Map.of("operation","RUN_CODING","requestId","exploration-drill",
                "datasetReference","authorized-source","instruction",instruction,"outputSchema",Map.of("type","object",
                    "properties",Map.of("statistic",Map.of("type","string")),"required",List.of("statistic"),"additionalProperties",false)),sources);
            if(!"COMPLETED".equals(receipt.get("status")) || !Boolean.TRUE.equals(receipt.get("resultAvailable"))
                    || !"INCONCLUSIVE".equals(receipt.get("validationStatus")) || ((List<?>)receipt.get("evidenceRefs")).size()!=6)
                throw new IllegalStateException("Exploration did not produce a committed structural result: "+receipt);
            String task=String.valueOf(receipt.get("taskId"));var grants=EnumSet.allOf(CodingTaskAccess.Action.class);
            var outcome=identity.as(kernel,grants,()->coding.get(kernel,task));
            if(outcome.usage().consumed().codeExecutions()<2)throw new IllegalStateException("Diagnostic and result-producing calls were not both executed");
            var numbers=rows.stream().map(row->new BigDecimal(row.get("measurement").toString())).sorted().toList();
            var expected=numbers.get(1).add(numbers.get(2)).divide(new BigDecimal("2"));
            identity.as(kernel,grants,()->{
                try(var result=collector.openResult(kernel,task,String.valueOf(receipt.get("datasetReference")))){
                    var actual=result.readPage(0,2).rows();
                    if(actual.size()!=1 || expected.compareTo(new BigDecimal(actual.get(0).get("statistic").toString()))!=0)
                        throw new IllegalStateException("Independent archived-result validation failed");
                    return true;
                }
            });
            var repeated=identity.as(kernel,grants,()->coding.resume(kernel,task));
            if(repeated.revision()!=outcome.revision())throw new IllegalStateException("Completed exploration replayed work");
            System.out.println("PRODUCTION_CODING_SMOKE PASS taskId="+task+" parentCalls=0 codingModelCalls="+outcome.usage().consumed().modelCalls()
                +" codeExecutions="+outcome.usage().consumed().codeExecutions()+" validationScope=STRUCTURAL_ONLY sixRefs=6 bundle="+outcome.evidenceBundleRef());
        }
    }
}
