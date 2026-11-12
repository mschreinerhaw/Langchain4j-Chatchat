package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.nativecline.NativeS3Settings;
import com.chatchat.api.model.PlatformModelCatalogService;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class NativeDynamicModelTest {
    @TempDir Path dir;
    final PlatformModelCatalogService catalog=mock(PlatformModelCatalogService.class);
    NativeS3Settings settings;
    @BeforeEach void setup() throws Exception {
        var p=dir.resolve("connection.json");Files.writeString(p,new ObjectMapper().writeValueAsString(Map.of("issuerKey","x".repeat(64),"endpoint","http://localhost:18360","defaultModel","old","dynamicModels",true)));
        settings=new NativeS3Settings(p.toString(),new ObjectMapper());settings.setCatalog(catalog);
    }
    ModelsConfig.ModelConnectionConfig model(String actual,String key){
        var c=new ModelsConfig.ModelConnectionConfig();c.setModelName(actual);c.setApiKey(key);c.setBaseUrl("https://provider.example/compatible-mode/v1/chat/completions/");c.setProtocol("openai");return c;
    }
    void published(String name,ModelsConfig.ModelConnectionConfig config){
        when(catalog.chatModelNames()).thenReturn(List.of(name));when(catalog.resolveChatModel(name)).thenReturn(new ModelsConfig.ResolvedModelConnection(name,name,config,ModelsConfig.ModelMatchType.CONFIG_KEY));
    }
    @Test void modelsAddedAfterStartupResolveWithoutStaticWhitelist(){
        published("chosen",model("actual-chosen","secret-a"));assertThat(settings.profile("chosen")).startsWith("runtime-");assertThat(settings.descriptor("chosen")).containsEntry("providerModel","actual-chosen");
        var first=settings.connection("chosen");assertThat(first).containsEntry("baseUrl","https://provider.example/compatible-mode/v1");
        published("chosen",model("actual-chosen","secret-b"));assertThat(settings.connection("chosen")).containsEntry("apiKey","secret-b");assertThat(first).containsEntry("apiKey","secret-a");
        var firstProfile=settings.profile("chosen");published("another",model("actual-other","secret-c"));assertThat(settings.profile("another")).isNotEqualTo(firstProfile);
    }
    @Test void unpublishedAndDisabledModelsAreNotReplacedWithDefault(){
        when(catalog.chatModelNames()).thenReturn(List.of());assertThatThrownBy(()->settings.profile("missing")).isInstanceOf(IllegalArgumentException.class);verify(catalog,never()).resolveChatModel(anyString());
    }
    @Test void defaultTracksPublishedCatalogAndProtocolCannotSilentlyChange(){
        when(catalog.defaultChatModel()).thenReturn("new-default");assertThat(settings.defaultModel()).isEqualTo("new-default");
        var c=model("actual","key");c.setProtocol("anthropic");published("chosen",c);assertThatThrownBy(()->settings.connection("chosen")).isInstanceOf(IllegalArgumentException.class);
    }
}
