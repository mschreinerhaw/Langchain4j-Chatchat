package com.chatchat.chat.task.event;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = DatabaseTerminalCommitTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseTerminalCommitTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses={AgentTaskLatestEntity.class,DatabaseAgentEventEntity.class})
    @EnableJpaRepositories(basePackageClasses={AgentTaskLatestRepository.class,DatabaseAgentEventRepository.class})
    static class Config {
        @Bean DatabaseAgentEventStore store(DatabaseAgentEventRepository events,AgentTaskLatestRepository tasks) {
            return new DatabaseAgentEventStore(events,tasks);
        }
    }
    @Autowired DatabaseAgentEventStore store;
    @SpyBean AgentTaskLatestRepository tasks;

    @Test void failureAfterEventFlushRollsBackTaskAndEventsThenRetryCommitsBothOnce() {
        var task = new AgentTaskLatestEntity();
        task.setTaskId("atomic-task");task.setTenantId("tenant");task.setUserId("owner");
        task.setSessionId("session");task.setStatus("RUNNING");
        tasks.saveAndFlush(task);
        var result = event("result","ANSWER");
        var complete = event("complete","COMPLETE");
        doThrow(new IllegalStateException("fault after event flush")).when(tasks).saveAndFlush(any());
        assertThrows(IllegalStateException.class,()->store.appendAll(List.of(result,complete)));
        reset(tasks);
        assertEquals("RUNNING",tasks.findById("atomic-task").orElseThrow().getStatus());
        assertTrue(store.listByTask("tenant","session","atomic-task",10).isEmpty());
        store.appendAll(List.of(result,complete));
        store.appendAll(List.of(result,complete));
        assertEquals("SUCCESS",tasks.findById("atomic-task").orElseThrow().getStatus());
        assertEquals(2,store.listByTask("tenant","session","atomic-task",10).size());
        assertNotNull(tasks.findById("atomic-task").orElseThrow().getFinalNotificationJson());
        assertEquals("full calculated answer", tasks.findById("atomic-task").orElseThrow().getAnswerSummary());
        assertTrue(tasks.findById("atomic-task").orElseThrow().getFinalNotificationJson().contains("retained-reference"));
    }

    private AgentEvent event(String id,String type) {
        return AgentEvent.builder().eventId(id).taskId("atomic-task").tenantId("tenant")
            .userId("owner").sessionId("session").eventScope("TASK").type(type).status("SUCCESS")
            .payload(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.Map.of(
                "answer", "artifact presentation", "citations", List.of(), "answerSummary", "full calculated answer",
                "finalNotificationJson", "{\"answer\":\"full calculated answer\",\"references\":[\"retained-reference\"]}"))
                .toString()).build();
    }
}
