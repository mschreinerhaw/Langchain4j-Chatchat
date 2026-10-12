package com.chatchat.agents.runtime.store;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.common.runtime.event.RuntimeEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEventLoggingTest {
    @Test void logsOrderedTransitionsBeforeFailingExternalPublisherAndDoesNotReplayDuplicates() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeEventLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            InMemoryAgentRunStore store = new InMemoryAgentRunStore(event -> { throw new IllegalStateException("publisher unavailable"); });
            AgentRunRequest request = AgentRunRequest.builder().runId("diagnostic-run").requestId("req-1").build();
            store.start(request);
            AgentObservation observation = AgentObservation.builder().source("runtime").type("tool")
                .metadata(Map.of("eventKind", "HARNESS_TURN", "eventState", "STARTED", "turn", 1)).build();
            store.recordObservation("diagnostic-run", observation);
            store.recordObservation("diagnostic-run", observation);
            store.fail("diagnostic-run", new IllegalStateException("TOOL_TIMEOUT"));
            assertThat(appender.list).hasSize(3);
            ObjectMapper json = new ObjectMapper();
            for (int i = 0; i < appender.list.size(); i++) {
                String message = appender.list.get(i).getFormattedMessage();
                var event = json.readTree(message.substring(message.indexOf('{')));
                assertThat(event.get("runId").asText()).isEqualTo("diagnostic-run");
                assertThat(event.get("sequence").asInt()).isEqualTo(i + 1);
            }
            assertThat(appender.list.get(1).getFormattedMessage()).contains("HARNESS_TURN", "STARTED");
            assertThat(appender.list.get(2).getFormattedMessage()).contains("RUN_FAILED", "TOOL_TIMEOUT");
            assertThat(store.find("diagnostic-run").orElseThrow().status().name()).isEqualTo("FAILED");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
