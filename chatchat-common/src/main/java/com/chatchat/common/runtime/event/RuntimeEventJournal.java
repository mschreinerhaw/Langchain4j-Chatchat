package com.chatchat.common.runtime.event;

import java.util.List;

/** Read port for the durable event history of one Runtime OS stream. */
public interface RuntimeEventJournal<E extends RuntimeEvent> {
    List<E> events(String streamId);
    List<E> events(String streamId, long afterOccurredAt, int limit);
}
