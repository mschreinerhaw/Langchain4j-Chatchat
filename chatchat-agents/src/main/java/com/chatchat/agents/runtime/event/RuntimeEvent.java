package com.chatchat.agents.runtime.event;

import java.util.Map;

/** Transport-neutral event contract emitted by Runtime OS components. */
public interface RuntimeEvent {

    String eventId();

    String streamId();

    String eventType();

    long occurredAt();

    Map<String, Object> payload();
}
