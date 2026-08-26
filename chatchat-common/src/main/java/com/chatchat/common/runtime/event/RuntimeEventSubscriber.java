package com.chatchat.common.runtime.event;

/** Extension port for one Runtime OS event consumer. */
@FunctionalInterface
public interface RuntimeEventSubscriber<E extends RuntimeEvent> {
    void onEvent(E event);
}
