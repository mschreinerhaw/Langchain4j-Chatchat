package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public inference identity only. Credentials are neither retained nor hashed. */
public final class NativeModelConfiguration {
    private NativeModelConfiguration(){}
    public static String fingerprint(ModelResourceRegistry registry,String selected){
        var c=registry.connection(selected);if(c==null)throw new SecurityException("Native model connection unavailable");
        var value=new LinkedHashMap<String,Object>();value.put("selectedModel",selected);
        value.put("providerModel",Objects.toString(c.getModelName(),""));value.put("protocol",Objects.toString(c.getProtocol(),""));
        // Retain no endpoint in the task/model prompt. Timeout remains transport policy, as in the existing Runtime.
        value.put("endpointSha256",com.chatchat.common.runtime.coding.NativeCodeWorkspace.sha(endpoint(c.getBaseUrl()).getBytes(StandardCharsets.UTF_8)));
        value.put("maxRetries",c.getMaxRetries());value.put("configuredMaxTokens",c.getMaxTokens());value.put("contextWindowTokens",c.getContextWindowTokens());
        return CodingCanonicalHash.sha256Value(value);
    }
    private static String endpoint(String configured){
        if(configured==null||configured.isBlank())return "";
        try{var uri=java.net.URI.create(configured);return new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toString();}
        catch(Exception invalid){throw new SecurityException("Invalid native model endpoint configuration");}
    }
}
