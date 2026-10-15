package com.chatchat.api.agent.task;

import com.chatchat.chat.task.core.AgentTaskResponse;
import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.chat.task.event.AgentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AgentTaskEventStreamServiceTest {
    final AgentTaskService tasks=mock(AgentTaskService.class);
    final AgentTaskEventStreamService streams=new AgentTaskEventStreamService(tasks);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new Surface(streams)).build();
    @AfterEach void close(){streams.shutdown();}

    @Test void successfulStepEventsCannotCompleteAnActiveTaskStream() throws Exception {
        var current=snapshot("WAIT_MODEL",2);
        when(tasks.get("tenant","task")).thenReturn(Optional.of(current));
        var events=List.of(event(1,"RUNTIME_OBSERVATION","SUCCESS"),event(2,"TOOL_RESULT","FAILED"));
        when(tasks.listEventsAfter(eq("tenant"),eq("task"),anyLong(),anyInt())).thenAnswer(call->
            page(events,call.getArgument(2),call.getArgument(3)));
        String body=read(0,100,200);
        assertThat(body).contains("id:1","id:2","event:timeout").doesNotContain("event:done");
    }

    @Test void completedTaskHistoryIsFullyDrainedAcrossPagesBeforeDone() throws Exception {
        var current=snapshot("SUCCESS",5);
        when(tasks.get("tenant","task")).thenReturn(Optional.of(current));
        var events=List.of(event(1,"QUESTION","PENDING"),event(2,"RUNTIME_OBSERVATION","SUCCESS"),
            event(3,"THINK","RUNNING"),event(4,"TOOL_RESULT","SUCCESS"),event(5,"ANSWER","SUCCESS"));
        when(tasks.listEventsAfter(eq("tenant"),eq("task"),anyLong(),anyInt())).thenAnswer(call->
            page(events,call.getArgument(2),call.getArgument(3)));
        String body=read(0,2,2000);
        assertThat(body).containsSubsequence("id:1","id:2","id:3","id:4","id:5","event:done");
        assertThat(body).contains("\"cursor\":5");
    }

    @Test void resumedStreamReadsOnlyTheSuffixAfterTheRequestedCursor() throws Exception {
        var current=snapshot("SUCCESS",4);
        when(tasks.get("tenant","task")).thenReturn(Optional.of(current));
        var events=List.of(event(1,"QUESTION","PENDING"),event(2,"THINK","RUNNING"),
            event(3,"TOOL_RESULT","SUCCESS"),event(4,"ANSWER","SUCCESS"));
        when(tasks.listEventsAfter(eq("tenant"),eq("task"),anyLong(),anyInt())).thenAnswer(call->
            page(events,call.getArgument(2),call.getArgument(3)));
        assertThat(read(2,1,2000)).doesNotContain("id:1","id:2").containsSubsequence("id:3","id:4","event:done");
    }

    @Test void terminalStatusDoesNotHideEventsStillBehindTheAuthoritativeCursor() throws Exception {
        var current=snapshot("SUCCESS",3);
        when(tasks.get("tenant","task")).thenReturn(Optional.of(current));
        var pages=new AtomicInteger();
        when(tasks.listEventsAfter(eq("tenant"),eq("task"),anyLong(),anyInt())).thenAnswer(call->
            pages.incrementAndGet()==1?List.of(event(1,"THINK","RUNNING")):
                List.of(event(2,"TOOL_RESULT","SUCCESS"),event(3,"ANSWER","SUCCESS")));
        assertThat(read(0,100,2000)).containsSubsequence("id:1","id:2","id:3","event:done");
        assertThat(pages).hasValue(2);
    }

    private String read(long cursor,int limit,long timeout) throws Exception {
        var pending=mvc.perform(get("/stream").param("cursor",String.valueOf(cursor)).param("limit",String.valueOf(limit))
            .param("timeout",String.valueOf(timeout))).andReturn();
        pending.getAsyncResult(4000);
        return mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
    }
    private static List<AgentEvent> page(List<AgentEvent> events,long cursor,int limit){
        return events.stream().filter(event->event.getSequence()>cursor).limit(limit).toList();
    }
    private static AgentEvent event(long sequence,String type,String status){
        return AgentEvent.builder().taskId("task").tenantId("tenant").sequence(sequence).type(type).status(status).build();
    }
    private static AgentTaskResponse snapshot(String status,long sequence){
        var response=mock(AgentTaskResponse.class);
        when(response.status()).thenReturn(status);when(response.lastEventSequence()).thenReturn(sequence);return response;
    }
    @RestController static class Surface {
        final AgentTaskEventStreamService streams;
        Surface(AgentTaskEventStreamService streams){this.streams=streams;}
        @GetMapping(value="/stream",produces="text/event-stream")
        SseEmitter stream(@RequestParam("cursor") long cursor,@RequestParam("limit") int limit,@RequestParam("timeout") long timeout){
            return streams.stream("tenant","task",cursor,limit,100,timeout);
        }
    }
}
