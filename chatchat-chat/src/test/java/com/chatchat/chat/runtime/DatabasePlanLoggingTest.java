package com.chatchat.chat.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.event.NoopAgentRunEventPublisher;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.common.runtime.event.RuntimeEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabasePlanLoggingTest {
    @Test void databaseOverrideRecordsPlanIdentityAndDependenciesWithoutToolInputs() {
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeEventLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            DatabaseAgentRunStore store = new DatabaseAgentRunStore(new NoopAgentRunEventPublisher(), new AgentRuntimeProperties(),
                mock(AgentRuntimeRunRepository.class), mock(AgentRuntimePlanRepository.class),
                mock(AgentRuntimeCheckpointRepository.class), new ObjectMapper().findAndRegisterModules());
            InterpretationPlan plan = new InterpretationPlan("1.0", null, null,
                new InterpretationPlan.Plan(List.of(new InterpretationPlan.Step(1, "mcp_tool", "authorized-reader",
                    Map.of("password", "secret-plan-input"), List.of(), null, null))), null, null);
            var saved = store.savePlan("tenant-1", "task-1", "plan-1", plan, "GENERATED");
            assertThat(saved.version()).isEqualTo(1);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                .contains("PLAN_SNAPSHOT_SAVED", "task-1", "plan-1", "\"planVersion\":1", "authorized-reader", "dependsOn")
                .doesNotContain("secret-plan-input", "password");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
