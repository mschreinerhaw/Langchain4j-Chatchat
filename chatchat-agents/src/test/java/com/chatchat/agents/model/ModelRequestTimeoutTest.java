package com.chatchat.agents.model;

import com.chatchat.common.config.ModelsConfig;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.orchestration.model.DeadlineAwareChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ModelRequestTimeoutTest {
    static Object field(Object value, String name) throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    static Duration sdkTimeout(ChatModel model, boolean streaming) throws Exception {
        Object provider=streaming?field(model,"provider"):model;
        return (Duration)field(field(field(provider,"client"),"httpClient"),"readTimeout");
    }
    static ModelsConfig configuration(String url) {
        var models=new ModelsConfig();
        for(String key:java.util.List.of("A","B","legacy")) {
            var connection=new ModelsConfig.ModelConnectionConfig();connection.setBaseUrl(url);
            connection.setApiKey("test-only");connection.setModelName("same-provider-model");connection.setMaxRetries(0);
            connection.setRequestTimeoutSeconds(key.equals("A")?Integer.valueOf(120):key.equals("B")?Integer.valueOf(600):null);
            connection.setTimeout(1); // Obsolete values must never shorten a selected model request.
            models.getChatModels().put(key,connection);
        }
        return models;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualSdkReceivesEachModelTimeoutAndPublishedChanges(boolean streaming) throws Exception {
        var models=configuration("http://127.0.0.1:1/v1");
        var factory=new ConfigurableChatModelFactory(models,new ObjectMapper());factory.setRuntimeStreamingEnabled(streaming);
        var resources=new com.chatchat.common.config.ModelResourceRegistry(models);
        for(var entry:java.util.Map.of("A",120,"B",600,"legacy",300).entrySet())
            assertThat(sdkTimeout(factory.createRuntime(resources.require(entry.getKey())),streaming)).isEqualTo(Duration.ofSeconds(entry.getValue()));
        models.getChatModels().get("B").setRequestTimeoutSeconds(900);
        assertThat(sdkTimeout(factory.createRuntime(resources.require("B")),streaming)).isEqualTo(Duration.ofSeconds(900));
        assertThat(sdkTimeout(factory.createResolved(resources.require("B"),Duration.ofSeconds(2),null),streaming)).isEqualTo(Duration.ofSeconds(2));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void delayedOrContinuouslyStreamingProviderIsReallyTerminated(boolean streaming) throws Exception {
        var disconnected=new CountDownLatch(1);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();
                if(streaming) {
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                    for(int i=0;i<40;i++) {
                        exchange.getResponseBody().write(("data: {\"id\":\"one\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n").getBytes());
                        exchange.getResponseBody().flush();Thread.sleep(100);
                    }
                } else {
                    Thread.sleep(3000);exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());
                }
            } catch(Exception closed) {disconnected.countDown();} finally {exchange.close();}
        });server.start();
        try {
            var models=configuration("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            models.getChatModels().get("A").setRequestTimeoutSeconds(1);
            var factory=new ConfigurableChatModelFactory(models,new ObjectMapper());factory.setRuntimeStreamingEnabled(streaming);
            var resources=new com.chatchat.common.config.ModelResourceRegistry(models);
            long start=System.nanoTime();
            assertThatThrownBy(()->factory.createRuntime(resources.require("A")).chat("delayed test"))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("Timeout");
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofMillis(700),Duration.ofMillis(2800));
            if(streaming)assertThat(disconnected.await(2,TimeUnit.SECONDS)).as("Provider stream socket closes").isTrue();
        } finally {server.stop(0);executor.shutdownNow();}
    }
    @Test void runtimeTaskDeadlineBeatsTheSelectedModelsLongerRequestTimeout() throws Exception {
        var models=configuration("http://127.0.0.1:1/v1");
        var selected=new AgentChatModelResolver(org.mockito.Mockito.mock(ChatModel.class),models).resolveChatModel("B");
        var timed=new DeadlineAwareChatModel(selected,()->0);
        assertThatThrownBy(()->timed.chat("do not dispatch")).hasMessageContaining("budget exhausted");
    }
    @Test void runtimeSwitchesModelsRefreshesExistingBindingsAndHonorsAnExpiringTask() throws Exception {
        var slow=new java.util.concurrent.atomic.AtomicBoolean();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                calls.incrementAndGet();exchange.getRequestBody().readAllBytes();if(slow.get())Thread.sleep(2000);
                byte[] body="{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } catch(Exception closed) { /* Cancellation closes the provider socket. */ } finally {exchange.close();}
        });server.start();
        try {
            var config=configuration("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var observed=new java.util.ArrayList<Duration>();
            var factory=new ConfigurableChatModelFactory(config,new ObjectMapper()) {
                @Override public ChatModel createRuntime(ModelsConfig.ResolvedModelConnection selected) {
                    var actual=super.createRuntime(selected);
                    try {observed.add(sdkTimeout(actual,false));} catch(Exception e){throw new AssertionError(e);}
                    return actual;
                }
            };
            var registry=new com.chatchat.common.config.ModelResourceRegistry(config);
            var published=org.mockito.Mockito.mock(com.chatchat.common.config.ModelCatalogOverride.class);
            org.mockito.Mockito.when(published.hasChatModels()).thenReturn(true);
            org.mockito.Mockito.when(published.resolveChatModel(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call->config.resolveChatModelConnection(call.getArgument(0)));
            registry.setCatalog(published);
            var resolver=new AgentChatModelResolver(org.mockito.Mockito.mock(ChatModel.class),registry,factory,new com.chatchat.agents.runtime.config.AgentRuntimeProperties());
            new DeadlineAwareChatModel(resolver.resolveChatModel("A"),()->-1L).chat("A");
            var held=resolver.resolveChatModel("B");
            new DeadlineAwareChatModel(held,()->-1L).chat("B");
            var updated=new ModelsConfig.ModelConnectionConfig();
            org.springframework.beans.BeanUtils.copyProperties(config.getChatModels().get("B"),updated);
            updated.setRequestTimeoutSeconds(900);config.getChatModels().put("B",updated);
            new DeadlineAwareChatModel(held,()->-1L).chat("B updated");
            assertThat(observed).containsExactly(Duration.ofSeconds(120),Duration.ofSeconds(600),Duration.ofSeconds(900));
            slow.set(true);long deadline=System.currentTimeMillis()+250;long start=System.nanoTime();
            assertThatThrownBy(()->new DeadlineAwareChatModel(held,()->Math.max(0,deadline-System.currentTimeMillis())).chat("task expires"))
                .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(1500));
            assertThat(calls.get()).isEqualTo(4);
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
