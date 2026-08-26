package com.chatchat.common.runtime.event;

/** Output port for delivering Runtime OS events to external consumers. */
@FunctionalInterface
public interface RuntimeEventPublisher<E extends RuntimeEvent> {
    void publish(E event);
}
