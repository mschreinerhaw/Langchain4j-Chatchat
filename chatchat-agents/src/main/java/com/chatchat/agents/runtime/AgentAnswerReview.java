package com.chatchat.agents.runtime;

public record AgentAnswerReview(
    String status,
    String answer,
    String feedback
) {

    public static final String ACCEPTED = "accepted";
    public static final String REVISED = "revised";
    public static final String REJECTED = "rejected";
}
