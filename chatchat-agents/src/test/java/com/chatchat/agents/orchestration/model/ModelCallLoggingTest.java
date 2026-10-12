package com.chatchat.agents.orchestration.model;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCallLoggingTest {
    @Test void startIsVisibleWhileDelegateIsRunningAndFailureRetainsCorrelationWithoutPrompt() {
        Logger logger = (Logger) LoggerFactory.getLogger(MeteredChatModel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        IllegalStateException failure = new IllegalStateException("private provider body");
        try {
            ChatModel delegate = new ChatModel() {
                @Override public String chat(String message) {
                    assertThat(appender.list).hasSize(1);
                    assertThat(appender.list.get(0).getFormattedMessage()).contains("status=STARTED", "runId=log-run", "callId=1");
                    throw failure;
                }
            };
            var usage = new LinkedHashMap<String,Object>(); usage.put("runId", "log-run");
            MeteredChatModel model = new MeteredChatModel(delegate, usage, 0, 0, 0, 0, 0.8);
            assertThatThrownBy(() -> model.chat("private model prompt")).isSameAs(failure);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(1).getFormattedMessage()).contains("status=FAILED", "runId=log-run", "callId=1", "IllegalStateException");
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString())
                .doesNotContain("private provider body", "private model prompt");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
