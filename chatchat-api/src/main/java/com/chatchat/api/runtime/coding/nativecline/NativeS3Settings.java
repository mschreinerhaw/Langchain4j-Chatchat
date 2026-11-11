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
    public NativeS3Settings(@Value("${chatchat.runtime.coding.unified.config-file}")String file,ObjectMapper json)throws Exception{
        Path path=Path.of(file);
        if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException("Private Native connection configuration required");
        var config=json.readTree(Files.readAllBytes(path));issuerKey=config.path("issuerKey").asText();defaultModel=config.path("defaultModel").asText();
        var values=new LinkedHashMap<String,String>();config.path("modelProfiles").fields().forEachRemaining(e->values.put(e.getKey(),e.getValue().asText()));profiles=Map.copyOf(values);
        if(issuerKey.length()<32||!profiles.containsKey(defaultModel))throw new IllegalArgumentException("Frozen Native model/issuer configuration required");
        client=new NativeS3NodeClient(URI.create(config.path("endpoint").asText()),json);
    }
    public String profile(String model){var profile=profiles.get(model);if(profile==null)throw new IllegalArgumentException("Selected model is not published on this Native node");return profile;}
}
