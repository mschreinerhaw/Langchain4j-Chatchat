package com.chatchat.api.runtime.coding.nativecline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deployment-owned connection capabilities. Never supplied in a task body. */
@Component
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeS3Settings {
    public final NativeS3NodeClient client;
    public final String issuerKey,defaultModel;
    public final Map<String,String> profiles;
    public final boolean dynamicModels;
    private com.chatchat.api.model.PlatformModelCatalogService catalog;
    @org.springframework.beans.factory.annotation.Autowired public void setCatalog(com.chatchat.api.model.PlatformModelCatalogService value){catalog=value;}
    public String defaultModel(){return dynamicModels?Objects.requireNonNull(catalog.defaultChatModel(),"No published default model"):defaultModel;}
    private com.chatchat.common.config.ModelsConfig.ModelConnectionConfig resolve(String model){
        if(catalog==null||!catalog.chatModelNames().contains(model))throw new IllegalArgumentException("Selected model is not published or is disabled");
        var resolved=catalog.resolveChatModel(model);
        if(resolved==null)throw new IllegalArgumentException("Selected model publication unavailable");
        return resolved.config();
    }
    public Map<String,String> descriptor(String model){
        if(!dynamicModels)return Map.of();
        var config=resolve(model);
        return Map.of("logicalName",model,"providerModel",config.getModelName());
    }
    public Map<String,String> connection(String model){
        var config=resolve(model);
        if(!Set.of("openai","auto").contains(Objects.toString(config.getProtocol(),"auto").toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Native model requires OpenAI compatible transport");
        var uri=URI.create(config.getBaseUrl());
        if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||config.getApiKey()==null||config.getApiKey().isBlank())throw new IllegalArgumentException("Invalid published model connection");
        String path=uri.getPath().replaceAll("/+$", "");
        if(path.endsWith("/chat/completions"))path=path.substring(0,path.length()-"/chat/completions".length());
        return Map.of("model",config.getModelName(),"baseUrl",uri.getScheme()+"://"+uri.getRawAuthority()+path,"apiKey",config.getApiKey());
    }
    public NativeS3Settings(@Value("${chatchat.runtime.coding.unified.config-file}")String file,ObjectMapper json)throws Exception{
        Path path=Path.of(file);
        if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException("Private Native connection configuration required");
        var config=json.readTree(Files.readAllBytes(path));dynamicModels=config.path("dynamicModels").asBoolean(false);issuerKey=config.path("issuerKey").asText();defaultModel=config.path("defaultModel").asText();
        var values=new LinkedHashMap<String,String>();config.path("modelProfiles").fields().forEachRemaining(e->values.put(e.getKey(),e.getValue().asText()));profiles=Map.copyOf(values);
        if(issuerKey.length()<32||!dynamicModels&&!profiles.containsKey(defaultModel))throw new IllegalArgumentException("Frozen Native model/issuer configuration required");
        client=new NativeS3NodeClient(URI.create(config.path("endpoint").asText()),json);
    }
    public String profile(String model){if(dynamicModels){var descriptor=descriptor(model);try{return "runtime-"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((descriptor.get("logicalName")+"\0"+descriptor.get("providerModel")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}var profile=profiles.get(model);if(profile==null)throw new IllegalArgumentException("Selected model is not published on this Native node");return profile;}
}
