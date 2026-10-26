package com.chatchat.agents.model;

import com.chatchat.agents.orchestration.model.*;
import com.chatchat.common.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeStreamingChatModelTest {
    @Test void realSseProviderRecordsFirstTokenAndGenerationWithoutPublishingPartialResults() throws Exception {
        var calls=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                Thread.sleep(30);
                output.write(("data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"provider\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":null}]}\n\n").getBytes());output.flush();
                Thread.sleep(30);
                output.write(("data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"provider\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\n\ndata: [DONE]\n\n").getBytes());output.flush();
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        });server.start();
        try {
            var files=new ModelsConfig();var connection=new ModelsConfig.ModelConnectionConfig();
            connection.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            connection.setApiKey("synthetic");connection.setProtocol("openai");connection.setModelName("provider");
            files.getChatModels().put("stream-test",connection);
            var factory=new ConfigurableChatModelFactory(files,new ObjectMapper());factory.setRuntimeStreamingEnabled(true);
            var provider=factory.createRuntime(new ModelResourceRegistry(files).require("stream-test"));
            try(var timing=MeteredChatModel.observeTiming()) {
                var response=new DeadlineAwareChatModel(provider,()->5000).chat(ChatRequest.builder().messages(UserMessage.from("test")).build());
                assertThat(response.aiMessage().text()).isEqualTo("OK");
                assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(4);
                assertThat(timing.responseMode()).isEqualTo("STREAMING");
                assertThat(timing.firstTokenMs()).isNotNull().isGreaterThanOrEqualTo(0);
                assertThat(timing.generationTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
            }
            assertThat(calls.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }

    @Test void deadlineCancelsSubscriptionBeforeFirstTokenAndRejectsLateCompletion() throws Exception {
        var cancelled=new AtomicBoolean();
        var callback=new AtomicReference<Flow.Subscriber<? super ChatModelStreamingEvent>>();
        StreamingChatModel provider=new StreamingChatModel() {
            @Override public Flow.Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
                return subscriber->{callback.set(subscriber);subscriber.onSubscribe(new Flow.Subscription(){
                    @Override public void request(long count) { }
                    @Override public void cancel() {cancelled.set(true);}
                });};
            }
        };
        var bounded=new DeadlineAwareChatModel(new RuntimeStreamingChatModel(provider),()->80);
        assertThatThrownBy(()->bounded.chat(ChatRequest.builder().messages(UserMessage.from("test")).build()))
            .isInstanceOf(AgentDeadlineExceededException.class);
        for(int i=0;i<100&&!cancelled.get();i++)Thread.sleep(5);
        assertThat(cancelled).isTrue();
        // A transport ignoring cancel cannot turn the already failed invocation into success.
        callback.get().onNext(new CompleteResponse(ChatResponse.builder()
            .aiMessage(dev.langchain4j.data.message.AiMessage.from("late")).build()));
        assertThat(cancelled).isTrue();
    }
}
