package com.chatchat.common.runtime.event;

import java.util.Map;

/** Transport-neutral event contract emitted by every Runtime OS component. */
public interface RuntimeEvent {
    String eventId();
    String streamId();
    String eventType();
    long occurredAt();
    /** Stream-local ordering when supported by the event journal; zero means unspecified. */
    default long sequence() { return 0L; }
    Map<String, Object> payload();
}
