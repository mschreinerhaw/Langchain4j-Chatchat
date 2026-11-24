import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GatewaySdkAcceptance {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ChatResponse streaming(OpenAiStreamingChatModel model, ChatRequest request, StringBuilder content) throws Exception {
        var done = new CompletableFuture<ChatResponse>();
        model.chat(request, new StreamingChatResponseHandler() {
            public void onPartialResponse(String token) { content.append(token); }
            public void onCompleteResponse(ChatResponse response) { done.complete(response); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
        });
        return done.get(15, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        var base = System.getenv("GATEWAY_BASE_URL");
        var key = System.getenv("SDK_GATEWAY_KEY");
        var alias = "sdk-model";
        var chat = OpenAiChatModel.builder().baseUrl(base).apiKey(key).modelName(alias).maxRetries(0).build();
        var stream = OpenAiStreamingChatModel.builder().baseUrl(base).apiKey(key).modelName(alias).build();
        var user = UserMessage.from("Please reply.");
        var plain = chat.chat(ChatRequest.builder().messages(user).build());
        require("网关正常".equals(plain.aiMessage().text()), "Unexpected ordinary reply");
        var partial = new StringBuilder();
        streaming(stream, ChatRequest.builder().messages(user).build(), partial);
        require(partial.toString().contains("你好"), "Missing streaming Chinese text");
        var tool = ToolSpecification.builder().name("gateway_echo").description("Resolve a fixture value")
            .parameters(JsonObjectSchema.builder().addStringProperty("value").required("value").build()).build();
        var initial = ChatRequest.builder().messages(user).toolSpecifications(List.of(tool)).build();
        var nativeCall = chat.chat(initial).aiMessage().toolExecutionRequests().get(0);
        require("gateway_echo".equals(nativeCall.name()) && nativeCall.arguments().contains("gateway-check"), "Missing native tool call");
        AiMessage streamedTool = streaming(stream, initial, new StringBuilder()).aiMessage();
        var streamedCall = streamedTool.toolExecutionRequests().get(0);
        require("call_fixture".equals(streamedCall.id()) && streamedCall.arguments().contains("gateway-check"), "Lost SSE tool fragments");
        var businessValue = "SDK_TASK_ARTIFACT_258";
        var toolResult = ToolExecutionResultMessage.from(streamedCall, businessValue);
        var finalResult = chat.chat(ChatRequest.builder().messages(user, streamedTool, toolResult).toolSpecifications(List.of(tool)).build());
        require(businessValue.equals(finalResult.aiMessage().text()), "Tool result did not reach the model");
        System.out.println("LangChain4j 1.20.0 HTTPS JSON + SSE + native tool round trip PASS");
    }
}
