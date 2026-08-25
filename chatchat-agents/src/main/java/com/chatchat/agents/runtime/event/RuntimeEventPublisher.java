package com.chatchat.agents.runtime.event;

/** Output port for delivering runtime events to external consumers. */
@FunctionalInterface
public interface RuntimeEventPublisher<E extends RuntimeEvent> {

    void publish(E event);
}
