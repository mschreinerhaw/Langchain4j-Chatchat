package com.chatchat.agents.orchestration;

import java.util.concurrent.CancellationException;

/** Signals that the request-level Agent deadline, rather than user cancellation, was exhausted. */
final class AgentDeadlineExceededException extends CancellationException {

    AgentDeadlineExceededException(String message) {
        super(message);
    }
}
