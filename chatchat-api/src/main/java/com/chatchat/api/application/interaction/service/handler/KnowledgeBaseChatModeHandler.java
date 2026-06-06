package com.chatchat.api.application.interaction.service.handler;

import com.chatchat.api.application.interaction.model.InteractionContext;
import com.chatchat.api.application.interaction.model.InteractionMode;
import com.chatchat.api.application.interaction.model.InteractionRequest;
import com.chatchat.api.application.interaction.model.InteractionResponse;
import com.chatchat.api.application.interaction.model.InteractionSource;
import com.chatchat.api.application.interaction.service.InteractionModeHandler;
import com.chatchat.api.rag.RAGService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Knowledge-base-backed chat handler (RAG mode).
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseChatModeHandler implements InteractionModeHandler {

    private static final int DEFAULT_TOP_K = 5;
    private final RAGService ragService;
    private final ChatModel chatLanguageModel;

    @Override
    public InteractionMode mode() {
        return InteractionMode.KNOWLEDGE_BASE_CHAT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        if (request.getKnowledgeBaseId() == null || request.getKnowledgeBaseId().isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId is required in knowledge_base_chat mode");
        }

        int topK = request.getMaxResults() == null ? DEFAULT_TOP_K : request.getMaxResults();
        List<Document> documents = ragService.retrieveContext(request.getKnowledgeBaseId(), request.getQuery(), topK);

        String prompt = ragService.buildContextualPrompt(
            request.getQuery(),
            documents,
            request.getSystemPrompt() == null ? "You are an enterprise knowledge assistant." : request.getSystemPrompt()
        );

        String answer = chatLanguageModel.chat(prompt);
        List<InteractionSource> sources = IntStream.range(0, documents.size())
            .mapToObj(i -> InteractionSource.builder()
                .rank(i + 1)
                .source(resolveSourceName(documents.get(i)))
                .snippet(shorten(documents.get(i).text(), 300))
                .build())
            .toList();

        return InteractionResponse.builder()
            .answer(answer)
            .sources(sources)
            .metadata(java.util.Map.of(
                "knowledgeBaseId", request.getKnowledgeBaseId(),
                "retrievedDocuments", documents.size(),
                "handler", "KnowledgeBaseChatModeHandler"
            ))
            .build();
    }

    private String resolveSourceName(Document document) {
        String fileName = document.metadata() != null
            ? document.metadata().getString(Document.FILE_NAME)
            : null;
        return fileName == null || fileName.isBlank() ? "inline_document" : fileName;
    }

    private String shorten(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
