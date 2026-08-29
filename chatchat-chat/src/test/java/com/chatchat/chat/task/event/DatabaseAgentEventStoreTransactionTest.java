package com.chatchat.chat.task.event;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseAgentEventStoreTransactionTest {

    @Test
    void eventStreamLockIsReleasedInAnIndependentTransaction() throws Exception {
        Method save = DatabaseAgentEventStore.class.getMethod("save", AgentEvent.class);
        Transactional transactional = save.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
