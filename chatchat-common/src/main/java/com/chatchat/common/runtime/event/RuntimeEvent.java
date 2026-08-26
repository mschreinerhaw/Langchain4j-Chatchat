package com.chatchat.common.runtime.event;

import java.util.Map;

/** Transport-neutral event contract emitted by every Runtime OS component. */
public interface RuntimeEvent {
    String eventId();
    String streamId();
    String eventType();
    long occurredAt();
    Map<String, Object> payload();
}
