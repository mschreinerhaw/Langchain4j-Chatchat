package com.chatchat.api.datascience.sql;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlWorkspaceMetadataToolsTest {
    final SqlStudioMetadataService metadata=mock(SqlStudioMetadataService.class);
    final SqlStudioMetadataGateway gateway=mock(SqlStudioMetadataGateway.class);
    final SqlGlotToolchain parser=mock(SqlGlotToolchain.class);
    final JdbcRuntimeEvidenceStore evidence=mock(JdbcRuntimeEvidenceStore.class);
    final ObjectProvider<SqlStudioMetadataService> provider=mock(ObjectProvider.class);
    final CodingTaskContract task=mock(CodingTaskContract.class);
    final Map<String,String> checkpoints=new HashMap<>();
    final Map<String,Object> schema=Map.of("projectId","project","discoveryId","discovery");
    final SqlStudioMetadataService.Context context=new SqlStudioMetadataService.Context("discovery","project","QUERY","mysql",List.of(
        new SqlStudioMetadataService.Binding("binding","service","asset","SOURCE","biz",null,"mysql")),"READY");
    SqlWorkspaceMetadataTools tools;
    String snapshot="v1";
    @BeforeEach void setup(){
        when(provider.getObject()).thenReturn(metadata);when(task.metadataRefs()).thenReturn(List.of(SqlWorkspaceMetadataTools.PROFILE));
        when(task.taskId()).thenReturn("task");when(task.scope()).thenReturn(new KernelDataScope("tenant","owner",null,null,"run",null,Map.of()));
        var budget=mock(CodingTaskContract.Budget.class);when(task.budget()).thenReturn(budget);when(budget.deadlineAt()).thenReturn(Instant.now().plusSeconds(60));
        when(evidence.readExecutionCheckpoint(any(),anyString())).thenAnswer(c->Optional.ofNullable(checkpoints.get(c.getArgument(1))));
        when(evidence.mutateExecutionCheckpoint(any(),anyString(),any())).thenAnswer(c->{String key=c.getArgument(1),next=c.<UnaryOperator<String>>getArgument(2).apply(checkpoints.get(key));checkpoints.put(key,next);return next;});
        when(evidence.contentHash(any())).thenAnswer(c->Integer.toHexString(c.getArgument(0).hashCode()));
        when(metadata.getScoped(any(),eq("project"),eq("discovery"))).thenReturn(context);
        when(metadata.operateScoped(any(),eq(context),any())).thenAnswer(c->{
            var args=c.<SqlStudioMetadataService.Operation>getArgument(2).arguments();
            if(args.containsKey("expectedSnapshotId")&&!snapshot.equals(args.get("expectedSnapshotId")))throw new ResponseStatusException(HttpStatus.CONFLICT,"SNAPSHOT_CONFLICT");
            return new SqlStudioMetadataGateway.Result("service",Map.of("status","SUCCEEDED","snapshotId",snapshot,"items",List.of(
                Map.of("tableRef","ref","tableName","trades","database","biz","columns",List.of(Map.of("name","client_no")),"relationsStatus","NOT_COLLECTED"))));
        });
        when(parser.validate(anyString(),anyList(),eq(Map.of()))).thenReturn(Map.of("valid",true,"references",List.of(Map.of("tableName","trades","database","","schema",""))));
        tools=new SqlWorkspaceMetadataTools(provider,gateway,parser,evidence,new ObjectMapper());
    }
    Map<String,Object> describe(){return Map.of("operation","DESCRIBE","bindingId","binding","tableName","trades");}
    @Test void keywordArraysReachPhysicalSearchAndDifferentConceptsDoNotReuseTheSameQuery(){
        tools.query(task,schema,Map.of("operation","SEARCH","bindingId","binding","queryTerms",List.of("customer","trade")));
        assertThat(tools.query(task,schema,Map.of("operation","SEARCH","bindingId","binding","queryTerms",List.of("asset"))))
            .doesNotContainKey("reusedEvidence");
        verify(metadata).operateScoped(any(),eq(context),argThat(op->"customer trade".equals(op.arguments().get("query"))));
        verify(metadata).operateScoped(any(),eq(context),argThat(op->"asset".equals(op.arguments().get("query"))));
        assertThatThrownBy(()->tools.query(task,schema,Map.of("operation","SEARCH","bindingId","binding","queryTerms",List.of(42))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotAbsenceRequestsDiscoveryInsteadOfCallingSqlInvalid(){
        assertThat(tools.validate(task,schema,List.of(new SqlOutputBundle.Candidate("q.sql","select client_no from trades")),"mysql"))
            .containsEntry("status","METADATA_REQUIRED").containsEntry("sqlValidity","UNDETERMINED");
    }
    @Test void reusesSameTaskEvidenceAfterRecheckingSnapshotAndRecoversAfterChange(){
        tools.query(task,schema,describe());assertThat(tools.query(task,schema,describe())).containsEntry("reusedEvidence",true);
        snapshot="v2";assertThatThrownBy(()->tools.query(task,schema,describe())).isInstanceOf(ResponseStatusException.class);
        assertThat(tools.query(task,schema,describe())).containsEntry("snapshotId","v2");
    }
    @Test void lineageAndDescribeShareEvidenceButRetainTheRequestedOperation(){
        tools.query(task,schema,describe());
        assertThat(tools.query(task,schema,Map.of("operation","LINEAGE","bindingId","binding","tableName","trades")))
            .containsEntry("operation","LINEAGE").containsEntry("lineageStatus","NOT_COLLECTED").containsEntry("reusedEvidence",true);
        assertThat(tools.query(task,schema,describe())).containsEntry("operation","DESCRIBE").doesNotContainKey("lineageStatus");
        verify(metadata,times(1)).operateScoped(any(),eq(context),argThat(op->"DESCRIBE".equals(op.arguments().get("operation"))));
        verify(metadata,times(2)).operateScoped(any(),eq(context),argThat(op->"SEARCH".equals(op.arguments().get("operation"))));
    }
    @Test void rejectsAnotherBindingAndExecutionArguments(){
        assertThatThrownBy(()->tools.query(task,schema,Map.of("operation","DESCRIBE","bindingId","other","tableName","secret"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tools.query(task,schema,Map.of("operation","SEARCH","sql","select secret"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNamespaceWideningInTheExistingBindingService(){
        when(metadata.operateScoped(any(),any(),any())).thenThrow(new SecurityException("outside selected database"));
        assertThatThrownBy(()->tools.query(task,schema,Map.of("operation","DESCRIBE","bindingId","binding","tableName","secret","database","other"))).isInstanceOf(SecurityException.class);
    }
    @Test void metadataOutageAndPermissionDenialAreDistinctFromInvalidSql(){
        assertThat(SqlWorkspaceMetadataTools.failure(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"offline")))
            .containsEntry("category","METADATA_SERVICE_UNAVAILABLE").containsEntry("retryable",true).containsEntry("sqlValidity","UNDETERMINED");
        assertThat(SqlWorkspaceMetadataTools.failure(new SecurityException("denied"))).containsEntry("category","AUTHORIZATION").containsEntry("retryable",false);
    }
    @Test void explicitlyReportsMissingObjectAndUncollectedLineage(){
        assertThat(tools.query(task,schema,Map.of("operation","LINEAGE","bindingId","binding","tableName","trades"))).containsEntry("lineageStatus","NOT_COLLECTED");
        when(metadata.operateScoped(any(),any(),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",Map.of("status","SUCCEEDED","snapshotId","v1","items",List.of())));
        assertThat(tools.query(task,schema,Map.of("operation","DESCRIBE","bindingId","binding","tableName","missing"))).containsEntry("status","NOT_FOUND");
    }
    @Test void businessDefinitionsReuseTheRegisteredToolWithTheAuthenticatedOwnerAndDeadline(){
        when(gateway.referenceScoped(any(),eq("service"),eq("enterprise_metadata_search"),anyMap())).thenReturn(Map.of("definitionStatus","NOT_FOUND"));
        var result=tools.query(task,schema,Map.of("operation","BUSINESS_SEARCH","bindingId","binding","query","profit and loss definition","queryTerms",List.of("profit")));
        assertThat(result).containsEntry("evidenceScope","ENTERPRISE_STANDARD_REFERENCES_NOT_PHYSICAL_TABLE_AUTHORITY");
        verify(gateway).referenceScoped(argThat(s->s.userId().equals("owner")&&s.tenantId().equals("tenant")&&s.attributes().containsKey("deadlineAt")),eq("service"),eq("enterprise_metadata_search"),anyMap());
    }
    @Test void businessConceptOnlyArrayWorksAndBroadRequestsGetRepairableBudgetFeedback(){
        when(gateway.referenceScoped(any(),eq("service"),eq("enterprise_metadata_search"),anyMap())).thenReturn(Map.of("definitionStatus","NOT_FOUND"));
        tools.query(task,schema,Map.of("operation","BUSINESS_SEARCH","bindingId","binding","queryTerms",List.of("profit")));
        verify(gateway).referenceScoped(any(),eq("service"),eq("enterprise_metadata_search"),argThat(args->!args.containsKey("query")&&List.of("profit").equals(args.get("queryTerms"))));
        var failure=catchThrowableOfType(()->tools.query(task,schema,Map.of("operation","BUSINESS_SEARCH","bindingId","binding","queryTerms",List.of("profit","assets"))),ResponseStatusException.class);
        assertThat(SqlWorkspaceMetadataTools.failure(failure)).containsEntry("category","QUERY_BUDGET").containsEntry("retryable",true);
    }
}
