package com.chatchat.agents.runtime.event;

/** Extension port for one runtime event consumer. */
@FunctionalInterface
public interface RuntimeEventSubscriber<E extends RuntimeEvent> {

    void onEvent(E event);
}
