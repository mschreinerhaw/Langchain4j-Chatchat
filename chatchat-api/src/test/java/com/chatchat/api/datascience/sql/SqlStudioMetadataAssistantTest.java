package com.chatchat.api.datascience.sql;

import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real owner-bound SQL persistence; model/MCP fixtures exercise protocol and security, not site acceptance. */
class SqlStudioMetadataAssistantTest {
    final ObjectMapper json=new ObjectMapper();
    final SqlStudioMetadataService metadata=mock(SqlStudioMetadataService.class);
    final SqlStudioMetadataGateway gateway=mock(SqlStudioMetadataGateway.class);
    final AgentChatModelResolver models=mock(AgentChatModelResolver.class);
    final AgentRunStore runs=mock(AgentRunStore.class);
    final RuntimeExecutionCheckpointPort checkpoints=mock(RuntimeExecutionCheckpointPort.class);
    final SqlGlotToolchain parser=mock(SqlGlotToolchain.class);
    final ChatModel model=mock(ChatModel.class);
    final Map<String,String> journal=new HashMap<>();
    final List<ChatResponse> replies=new ArrayList<>();
    JdbcTemplate jdbc;SqlStudioMetadataAssistant assistant;
    final Map<String,Object> table=Map.of("tableRef","table-ref","tableName","schedule_history","database","scheduler","columns",List.of(
        Map.of("name","status","dataType","varchar","comment","Documented execution status"),Map.of("name","started_at","dataType","timestamp")),"relationsStatus","NOT_COLLECTED");
    final SqlStudioMetadataService.Context context=new SqlStudioMetadataService.Context("discovery","project","QUERY","mysql",List.of(
        new SqlStudioMetadataService.Binding("binding","service","asset","SOURCE",null,null,"mysql")),"READY");
    static String checkpointKey(KernelDataScope scope,String key){return scope.tenantId()+":"+scope.userId()+":"+scope.runId()+":"+key;}
    @BeforeEach void setup()throws Exception{
        var datasource=new JdbcDataSource();datasource.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(datasource);
        jdbc.execute("create table ds_sql_project(id varchar primary key,tenant_id varchar,owner_id varchar)");
        jdbc.execute("create table ds_sql_generation(id varchar primary key,project_id varchar,context_json clob)");
        jdbc.update("insert into ds_sql_project values('project','tenant','owner')");
        when(checkpoints.readExecutionCheckpoint(any(),anyString())).thenAnswer(c->Optional.ofNullable(journal.get(checkpointKey(c.getArgument(0),c.getArgument(1)))));
        when(checkpoints.mutateExecutionCheckpoint(any(),anyString(),any())).thenAnswer(c->{String key=checkpointKey(c.getArgument(0),c.getArgument(1));String next=c.<UnaryOperator<String>>getArgument(2).apply(journal.get(key));journal.put(key,next);return next;});
        when(checkpoints.compareAndSetExecutionCheckpoint(any(),anyString(),any(),anyString())).thenAnswer(c->{String key=checkpointKey(c.getArgument(0),c.getArgument(1));if(!Objects.equals(journal.get(key),c.getArgument(2)))return false;journal.put(key,c.getArgument(3));return true;});
        when(metadata.getScoped(any(),eq("project"),eq("discovery"))).thenReturn(context);
        when(metadata.operateScoped(any(),eq(context),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",result("snapshot")));
        when(models.resolveChatModel("model")).thenReturn(model);when(runs.find(anyString())).thenReturn(Optional.empty());
        when(model.chat(any(ChatRequest.class))).thenAnswer(c->{assertThat(replies).isNotEmpty();return replies.remove(0);});
        assistant=new SqlStudioMetadataAssistant(metadata,gateway,models,runs,checkpoints,parser,jdbc,json);
    }
    Map<String,Object> result(String snapshot){return Map.of("contractVersion",SqlStudioMetadataGateway.CONTRACT,"doesNotExecuteSql",true,"status","SUCCEEDED","snapshotId",snapshot,"items",List.of(table));}
    InteractionContext runtimeContext(){return InteractionContext.builder().requestId("request").conversationId("session").build();}
    InteractionRequest request(String id)throws Exception{
        var prepared=new AgentTaskSubmitRequest();prepared.setSessionId("session");prepared.setQuery("Find scheduling tables");prepared.setModelName("model");
        prepared.setToolInput(Map.of("sqlStudioAction","explore","sqlMetadataDiscoveryId","discovery"));
        jdbc.update("insert into ds_sql_generation values(?,?,?)",id,"project",json.writeValueAsString(prepared));
        return InteractionRequest.builder().tenantId("tenant").userId("owner").conversationId("session").query(prepared.getQuery()).modelName("model")
            .toolInput(new HashMap<>(Map.of("sqlMetadataGenerationId",id,"__agentRunId","run-"+id))).build();
    }
    ChatResponse tool(String asset){return ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("call").name("sql_schema_context_query")
        .arguments("{\"operation\":\"SEARCH\",\"assetId\":\""+asset+"\",\"query\":\"scheduler\"}").build())).build()).build();}
    ChatResponse answer(String column)throws Exception{return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(Map.of("notes","Scheduling candidates are supported by the indexed fields; no SQL was executed.","files",List.of(),"candidateTables",List.of(Map.of("tableRef","table-ref","columns",List.of(column),"reason","Execution metadata")))))).build();}
    @Test void reusesReauthorizedEvidenceAcrossRoundsWithoutCreatingCodingOrQueryingTheSameSchema()throws Exception{
        replies.add(tool("asset"));replies.add(answer("status"));var a=assistant.answer(request("a"),runtimeContext());
        var first=(Map<?,?>)a.getMetadata().get("sqlMetadataExploration");assertThat(((Map<?,?>)first.get("stats")).get("toolCalls")).isEqualTo(1);
        clearInvocations(metadata,model);replies.add(answer("status"));var b=assistant.answer(request("b"),runtimeContext());
        var second=(Map<?,?>)b.getMetadata().get("sqlMetadataExploration");assertThat(((Map<?,?>)second.get("stats")).get("toolCalls")).isEqualTo(0);
        verify(model).chat(argThat((ChatRequest r)->r.messages().toString().contains("schedule_history")));
        verify(metadata,times(2)).operateScoped(any(),eq(context),argThat(op->"SEARCH".equals(op.arguments().get("operation"))&&Boolean.FALSE.equals(op.arguments().get("includeColumns"))));
        assertThat(b.getMetadata()).containsEntry("codingWorkspaceInitialized",false);verifyNoInteractions(parser,gateway);
    }
    @Test void completedTurnResumesWithoutAnotherModelOrSqlInvocationButRechecksGrantsAndSnapshot()throws Exception{
        replies.add(tool("asset"));replies.add(answer("status"));var request=request("resume");var first=assistant.answer(request,runtimeContext());
        clearInvocations(model,parser,metadata);var second=assistant.answer(request,runtimeContext());
        assertThat(second.getAnswer()).isEqualTo(first.getAnswer());verifyNoInteractions(model,parser);
        verify(metadata).getScoped(any(),eq("project"),eq("discovery"));verify(metadata).operateScoped(any(),eq(context),any());
    }
    @Test void explicitSqlExecutionArgumentsAreRejectedBeforeCallingMcp()throws Exception{
        replies.add(ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("unsafe").name("sql_schema_context_query")
            .arguments("{\"operation\":\"SEARCH\",\"assetId\":\"asset\",\"sql\":\"SELECT * FROM private\"}").build())).build()).build());
        assertThatThrownBy(()->assistant.answer(request("unsafe"),runtimeContext())).isInstanceOf(SecurityException.class);
        verify(metadata,never()).operateScoped(any(),any(),any());verifyNoInteractions(parser);
    }
    @Test void oversizedToolResultReturnsBoundedRetryGuidanceInsteadOfFailingTheTask()throws Exception{
        var huge=new LinkedHashMap<>(result("snapshot"));huge.put("items",List.of(Map.of("tableRef","oversized","comment","x".repeat(160000))));
        when(metadata.operateScoped(any(),eq(context),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",huge));
        replies.add(tool("asset"));replies.add(ChatResponse.builder().aiMessage(AiMessage.from("{\"notes\":\"The metadata response was too large; no fields can be inferred.\",\"files\":[],\"candidateTables\":[]}")).build());
        var response=assistant.answer(request("large"),runtimeContext());
        assertThat(response.getAnswer()).contains("no fields can be inferred");
        verify(model).chat(argThat((ChatRequest r)->r.messages().stream().anyMatch(message->message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage result&&result.text().contains("RESPONSE_TOO_LARGE"))));
        assertThat(journal.values()).noneMatch(value->value.contains("x".repeat(1000)));
    }
    @Test void budgetClosesIntoAnEvidenceBasedAnswerWithoutExecutingExtraToolCalls()throws Exception{
        var batch=java.util.stream.IntStream.range(0,26).mapToObj(i->ToolExecutionRequest.builder().id("call-"+i).name("sql_schema_context_query")
            .arguments("{\"operation\":\"SEARCH\",\"assetId\":\"asset\",\"query\":\"scheduler\"}").build()).toList();
        replies.add(ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(batch).build()).build());replies.add(answer("status"));
        var response=assistant.answer(request("budget"),runtimeContext());var result=(Map<?,?>)response.getMetadata().get("sqlMetadataExploration");
        assertThat(((Map<?,?>)result.get("stats")).get("toolCalls")).isEqualTo(24);
        verify(model).chat(argThat((ChatRequest r)->r.toolSpecifications()==null||r.toolSpecifications().isEmpty()));
        assertThat(response.getAnswer()).contains("Scheduling candidates");
    }
    @Test void modelCanCorrectHarmlessSchemaErrorsWithoutExecutingTheInvalidRequest()throws Exception{
        replies.add(ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("mistake").name("sql_schema_context_query")
            .arguments("{\"operation\":\"DESCRIBE\",\"assetId\":\"asset\",\"tableRef\":\"table-ref\"}").build())).build()).build());
        replies.add(tool("asset"));replies.add(answer("status"));var response=assistant.answer(request("corrected"),runtimeContext());
        assertThat(response.getAnswer()).contains("Scheduling candidates");verify(metadata,never()).operateScoped(any(),any(),argThat(op->op.arguments().containsKey("tableRef")));
        verify(model,times(3)).chat(any(ChatRequest.class));
    }
    @Test void singleStoredBindingResolvesAnOmittedAssetAndToolSchemaConstrainsExplicitAssetChoices()throws Exception{
        replies.add(ChatResponse.builder().aiMessage(AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("single").name("sql_schema_context_query")
            .arguments("{\"operation\":\"SEARCH\",\"query\":\"scheduler\"}").build())).build()).build());replies.add(answer("status"));
        assertThat(assistant.answer(request("single"),runtimeContext()).getAnswer()).contains("Scheduling candidates");
        var requests=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(0).toolSpecifications().get(0).toJson()).contains("\"enum\":[\"asset\"]");
    }
    @Test void modelCannotPublishExampleFailureCodesByPassingOnlyPhysicalFieldValidation()throws Exception{
        when(parser.validate(anyString(),anyList(),anyMap())).thenReturn(Map.of("valid",true,"fieldValidation","CHECKED","requiresBusinessEvidence",true,"businessLiteralValues",List.of("03")));
        replies.add(tool("asset"));replies.add(ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(Map.of(
            "notes","Assume 03 means failed; replace this example later.","files",List.of(Map.of("path","sql/rate.sql","content","SELECT SUM(CASE WHEN status='03' THEN 1 ELSE 0 END) FROM schedule_history")),
            "businessDefinitions",List.of(Map.of("tableRef","table-ref","column","status","source","METADATA_COMMENT","quote","03 means failed")))))).build());
        var response=assistant.answer(request("unconfirmed"),runtimeContext());var output=json.readTree(response.getAnswer());var result=(Map<?,?>)response.getMetadata().get("sqlMetadataExploration");
        assertThat(output.path("files").isEmpty()).isTrue();assertThat(output.path("notes").asText()).doesNotContain("03");
        assertThat(result.get("semanticValidation")).isEqualTo("INSUFFICIENT_BUSINESS_EVIDENCE");assertThat(result.get("blockedSqlProposalCount")).isEqualTo(1);
    }
    @Test void documentedBusinessLiteralsCanPublishAValidatedMetricProposal()throws Exception{
        var source=new LinkedHashMap<>(table);source.put("columns",List.of(Map.of("name","status","dataType","varchar","comment","03 means failed; 09 means cancelled")));
        var response=new LinkedHashMap<>(result("snapshot"));response.put("items",List.of(source));when(metadata.operateScoped(any(),eq(context),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",response));
        when(parser.validate(anyString(),anyList(),anyMap())).thenReturn(Map.of("valid",true,"fieldValidation","CHECKED","requiresBusinessEvidence",true,"businessLiteralValues",List.of("03")));
        replies.add(tool("asset"));replies.add(ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(Map.of("notes","Documented definition used, SQL remains a proposal.","files",List.of(Map.of("path","sql/rate.sql","content","SELECT SUM(CASE WHEN status='03' THEN 1 ELSE 0 END) FROM schedule_history")),
            "businessDefinitions",List.of(Map.of("tableRef","table-ref","column","status","source","METADATA_COMMENT","quote","03 means failed")))))).build());
        var answer=assistant.answer(request("confirmed"),runtimeContext());assertThat(json.readTree(answer.getAnswer()).path("files").size()).isEqualTo(1);
        assertThat(((Map<?,?>)answer.getMetadata().get("sqlMetadataExploration")).get("semanticValidation")).isEqualTo("DEFINITIONS_REFERENCED");
    }
    @Test void historicalSqlWithoutBusinessEvidencePolicyIsFlaggedWithoutOverwritingItsOriginalRecord()throws Exception{
        var prepared=new AgentTaskSubmitRequest();prepared.setTenantId("tenant");prepared.setUserId("owner");prepared.setToolInput(Map.of("sqlMetadataDiscoveryId","discovery"));
        var scope=new KernelDataScope("tenant","owner",null,null,"sql-metadata:discovery",null,Map.of());
        String saved=encode(Map.of("answer",encode(Map.of("files",List.of(Map.of("path","sql/legacy.sql","content","SELECT status FROM schedule_history")),"notes","Historical proposal")),"result",Map.of("discoveryId","discovery","snapshots",Map.of())));
        journal.put(checkpointKey(scope,"turn:legacy"),saved);when(metadata.get(any(),eq("project"),eq("discovery"))).thenReturn(context);
        var generation=new SqlStudioService.Generation("legacy","task",false,new SqlStudioService.Prepared("legacy",prepared),Map.of());
        assertThat(assistant.review(new org.springframework.mock.web.MockHttpServletRequest(),"project",generation)).containsEntry("semanticValidation","REVALIDATION_REQUIRED").containsEntry("blockedSqlProposalCount",1);
        assertThat(journal.get(checkpointKey(scope,"turn:legacy"))).isEqualTo(saved);verifyNoInteractions(model,parser);
    }
    String encode(Object value)throws Exception{return json.writeValueAsString(value);}
    @Test void malformedExplorationCannotFallBackToTheCodingRoute(){
        var malformed=InteractionRequest.builder().tenantId("tenant").userId("owner").query("Find tables").toolInput(Map.of("sqlStudioAction","explore")).build();
        assertThatThrownBy(()->assistant.supports(malformed)).isInstanceOf(SecurityException.class);verifyNoInteractions(metadata,models);
    }
    @Test void unrelatedMetadataDigitsCannotBeUsedAsAConfirmedFailureCode()throws Exception{
        var source=new LinkedHashMap<>(table);source.put("columns",List.of(Map.of("name","status","dataType","varchar","comment","2030 is the metadata edition")));
        var data=new LinkedHashMap<>(result("snapshot"));data.put("items",List.of(source));when(metadata.operateScoped(any(),eq(context),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",data));
        when(parser.validate(anyString(),anyList(),anyMap())).thenReturn(Map.of("valid",true,"fieldValidation","CHECKED","requiresBusinessEvidence",true,"businessLiteralValues",List.of("03")));
        replies.add(tool("asset"));replies.add(ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(Map.of("notes","Bad inference from a version number","files",List.of(Map.of("path","sql/rate.sql","content","SELECT SUM(CASE WHEN status='03' THEN 1 ELSE 0 END) FROM schedule_history")),
            "businessDefinitions",List.of(Map.of("tableRef","table-ref","column","status","source","METADATA_COMMENT","quote","2030 is the metadata edition")))))).build());
        var response=assistant.answer(request("incidental"),runtimeContext());assertThat(json.readTree(response.getAnswer()).path("files").isEmpty()).isTrue();
    }
    @Test void forgedScopeAndAlteredModelCannotEnterMetadataTools()throws Exception{
        var request=request("forged");request.setUserId("other");assertThatThrownBy(()->assistant.supports(request)).isInstanceOf(SecurityException.class);
        request.setUserId("owner");request.setModelName("other-model");assertThatThrownBy(()->assistant.supports(request)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(metadata,models);
    }
    @Test void unboundAssetsAndExecutionArgumentsAreRejectedBeforeMcpExecution()throws Exception{
        replies.add(tool("unbound"));assertThatThrownBy(()->assistant.answer(request("bad"),runtimeContext())).isInstanceOf(SecurityException.class);
        verify(metadata,never()).operateScoped(any(),any(),any());verifyNoInteractions(gateway,parser);
    }
    @Test void changedSnapshotBeforePublicationFailsWithoutAskingTheModelToRepairStaleEvidence()throws Exception{
        when(metadata.operateScoped(any(),eq(context),any())).thenReturn(new SqlStudioMetadataGateway.Result("service",result("snapshot")),new SqlStudioMetadataGateway.Result("service",result("changed")));
        replies.add(tool("asset"));replies.add(answer("status"));assertThatThrownBy(()->assistant.answer(request("changed"),runtimeContext())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("SNAPSHOT_CONFLICT");
        verify(model,times(2)).chat(any(ChatRequest.class));assertThat(journal.keySet()).noneMatch(key->key.endsWith(":turn:changed"));
    }
    @Test void revokedAccessPreventsPreviouslyPersistedEvidenceFromReachingTheNextModel()throws Exception{
        replies.add(tool("asset"));replies.add(answer("status"));assistant.answer(request("first"),runtimeContext());clearInvocations(model);
        when(metadata.getScoped(any(),anyString(),anyString())).thenThrow(new SecurityException("Grant revoked"));
        assertThatThrownBy(()->assistant.answer(request("revoked"),runtimeContext())).isInstanceOf(SecurityException.class);verifyNoInteractions(model);
    }
    @Test void aHiddenOrInventedColumnNeverBecomesAnAcceptedCandidate()throws Exception{
        replies.add(tool("asset"));for(int i=0;i<3;i++)replies.add(answer("secret_password"));
        assertThatThrownBy(()->assistant.answer(request("hidden"),runtimeContext())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hidden column");
        assertThat(journal.keySet()).noneMatch(key->key.endsWith(":turn:hidden"));
    }
    @Test void cancellationStopsTheConversationBeforeAnyMetadataOperation()throws Exception{
        var request=request("cancel");request.getToolInput().put("__agentCancellation",(java.util.function.BooleanSupplier)()->true);
        assertThatThrownBy(()->assistant.answer(request,runtimeContext())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(model);verify(metadata,never()).operateScoped(any(),any(),any());
    }
}
