package com.chatchat.agents.runtime.evaluation;

import java.util.List;

public record AgentRegressionCase(
    String id,
    String name,
    List<String> tags,
    Input input,
    Expected expected
) {

    public AgentRegressionCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        input = input == null ? new Input("") : input;
        expected = expected == null ? new Expected(null, null, null, null) : expected;
    }

    public record Input(
        String query
    ) {
    }

    public record Expected(
        Retrieval retrieval,
        Evidence evidence,
        Review review,
        Answer answer
    ) {
        public Expected {
            retrieval = retrieval == null ? new Retrieval(List.of()) : retrieval;
            evidence = evidence == null ? new Evidence(0, List.of(), null) : evidence;
            review = review == null ? new Review(true, false, null, null) : review;
            answer = answer == null ? new Answer(List.of()) : answer;
        }
    }

    public record Retrieval(
        List<String> mustContain
    ) {
        public Retrieval {
            mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        }
    }

    public record Evidence(
        int minChunks,
        List<String> mustContainKeywords,
        Double minScore
    ) {
        public Evidence {
            mustContainKeywords = mustContainKeywords == null ? List.of() : List.copyOf(mustContainKeywords);
        }
    }

    public record Review(
        boolean mustPass,
        boolean allowPartialEvidence,
        Double maxRejectRate,
        Double minScore
    ) {
    }

    public record Answer(
        List<String> mustContainAny
    ) {
        public Answer {
            mustContainAny = mustContainAny == null ? List.of() : List.copyOf(mustContainAny);
        }
    }
}
