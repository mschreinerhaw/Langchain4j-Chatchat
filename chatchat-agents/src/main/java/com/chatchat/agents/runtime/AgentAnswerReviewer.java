package com.chatchat.agents.runtime;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

public interface AgentAnswerReviewer {

    AgentAnswerReview review(ChatModel chatModel,
                             String query,
                             String systemPrompt,
                             List<String> observations,
                             String answer);
}
