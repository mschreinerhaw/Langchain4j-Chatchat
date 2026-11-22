package com.chatchat.api.runtime.coding.monitor;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingMonitorProjectionReplayTest {
    @Test void repeatedJournalReplayDoesNotAttemptDuplicateInserts() {
        Fixture f=new Fixture();
        for(int i=0;i<20;i++)f.publish(f.event("original"));
        assertThat(f.jdbc.queryForObject("select count(*) from runtime_coding_event_view",Integer.class)).isEqualTo(1);
        assertThat(f.insertAttempts()).isEqualTo(1);
        assertThat(f.projection.events("tenant","owner","run","task",0,10)).hasSize(1);
        assertThat(f.projection.events("tenant","other","run","task",0,10)).isEmpty();
    }

    @Test void conflictingSourceIdentityStillBlocksProjectionWithoutOverwritingOrDuplicateSql() {
        Fixture f=new Fixture();f.publish(f.event("original"));f.publish(f.event("conflicting"));
        assertThat(f.insertAttempts()).isEqualTo(1);
        assertThat(f.projection.events("tenant","owner","run","task",0,10).get(0)).containsEntry("summary","original");
        assertThat(f.projection.list("tenant","owner","","","",0,Long.MAX_VALUE,0,10)).containsEntry("projectionLagging",true);
    }

    @Test void listsTheStoredSubmitterForAnExistingTaskWithinTheAuthenticatedOwnerScope() {
        Fixture f = new Fixture();
        f.publish(f.event("original"));
        var items = (List<?>) f.projection.list("tenant", "owner", "alice", "", "", 0, Long.MAX_VALUE, 0, 10).get("items");
        assertThat(items).hasSize(1);
        var task = (Map<?, ?>) items.get(0);
        assertThat(task.get("submitterUsername")).isEqualTo("alice");
        assertThat(task.get("submitterUserId")).isEqualTo("owner");
        var fallback = (List<?>) f.projection.list("tenant", "owner", "", "", "", 0, Long.MAX_VALUE, 0, 10).get("items");
        assertThat(((Map<?, ?>) fallback.get(0)).get("submitterUserId")).isEqualTo("owner");
        assertThat((List<?>) f.projection.list("tenant", "other", "bob", "", "", 0, Long.MAX_VALUE, 0, 10).get("items")).isEmpty();
        assertThat((List<?>) f.projection.list("other", "owner", "alice", "", "", 0, Long.MAX_VALUE, 0, 10).get("items")).isEmpty();
    }

    static class Fixture {
        final JdbcTemplate jdbc=spy(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1")));
        final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        final CodingTaskContract contract=mock(CodingTaskContract.class,RETURNS_DEEP_STUBS);
        final CodingTaskOutcome outcome=mock(CodingTaskOutcome.class,RETURNS_DEEP_STUBS);
        final Instant at=Instant.parse("2026-11-15T00:00:00Z");
        final CodingMonitorProjection projection;
        Fixture(){
            when(contract.scope()).thenReturn(scope);when(contract.taskId()).thenReturn("task");when(contract.objective().instruction()).thenReturn("Replay test");
            when(outcome.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);
            when(outcome.createdAt()).thenReturn(at);when(outcome.updatedAt()).thenReturn(at);when(outcome.revision()).thenReturn(1L);
            projection=new CodingMonitorProjection(jdbc,new ObjectMapper().findAndRegisterModules(),mock(DurableCodingTaskService.class),mock(JdbcRuntimeEvidenceStore.class));
            projection.schema();
        }
        CodingExecutionEvent event(String text){
            var event=mock(CodingExecutionEvent.class);
            when(event.scope()).thenReturn(scope);when(event.taskId()).thenReturn("task");when(event.eventId()).thenReturn("event-1");
            when(event.sequence()).thenReturn(1L);when(event.occurredAt()).thenReturn(at);when(event.type()).thenReturn(CodingExecutionEvent.Type.TASK_STARTED);
            when(event.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);when(event.summary()).thenReturn(text);when(event.payload()).thenReturn(Map.of());when(event.evidenceRefs()).thenReturn(List.of());
            return event;
        }
        void publish(CodingExecutionEvent event){projection.committed(contract,outcome,List.of(event));projection.flush();}
        long insertAttempts(){return mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")&&i.getMethod().isVarArgs()&&i.getArguments()[0].toString().startsWith("insert into runtime_coding_event_view")).count();}
    }
}
