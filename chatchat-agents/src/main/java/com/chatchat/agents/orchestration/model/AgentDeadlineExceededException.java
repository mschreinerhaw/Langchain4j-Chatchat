package com.chatchat.agents.orchestration.model;

import java.util.concurrent.CancellationException;

/** Signals that the request-level Agent deadline, rather than user cancellation, was exhausted. */
public final class AgentDeadlineExceededException extends CancellationException {

    public AgentDeadlineExceededException(String message) {
        super(message);
    }
}
