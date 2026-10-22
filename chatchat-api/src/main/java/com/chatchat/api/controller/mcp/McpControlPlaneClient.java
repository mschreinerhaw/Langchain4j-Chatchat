package com.chatchat.api.controller.mcp;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Fixed-origin internal management client. It never returns service credentials to browsers. */
@Component @RequiredArgsConstructor
public class McpControlPlaneClient {
    private final Environment environment;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String bearer;
    private volatile long expiresAt;
    public JsonNode request(String method,String path,JsonNode body) {
        if (!credentials.isEnabled() || credentials.resolvedSecret().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"MCP control-plane credential is unavailable");
        try {
            try { return send(method,path,body,token()); }
            catch(ResponseStatusException ex) {
                if(ex.getStatusCode().value()!=401)throw ex;
                bearer=null; return send(method,path,body,token());
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"MCP interrupted"); }
        catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"MCP management service unavailable"); }
    }
    private synchronized String token() throws Exception {
        if(bearer!=null && expiresAt>System.currentTimeMillis()+5000)return bearer;
        JsonNode login=send("POST","/api/v1/admin/auth/login",json.valueToTree(Map.of(
            "username",credentials.resolvedUsername(),"password",credentials.resolvedSecret())),null);
        String issued=login.path("token").asText();
        if(issued.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"MCP service login returned no token");
        expiresAt=login.path("expiresAt").asLong();bearer=issued;return issued;
    }
    private JsonNode send(String method,String path,JsonNode body,String token) throws Exception {
        String origin=environment.getRequiredProperty("chatchat.mcp.center.base-url").replaceAll("/+$","");
        var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(45))
            .header("Content-Type","application/json");
        if (token!=null) request.header("Authorization","Bearer "+token);
        var result=http.send(request.method(method,body==null ? HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode envelope;
        try { envelope=json.readTree(result.body()); } catch(Exception ex) { envelope=json.createObjectNode(); }
        if (result.statusCode()<200 || result.statusCode()>=300 || (envelope.has("code") && envelope.path("code").asInt()!=200)) {
            int status=result.statusCode()>=400 ? result.statusCode():envelope.path("code").asInt(400);
            if(status<400 || status>599)status=502;
            String message=envelope.path("message").asText("MCP management request rejected");
            throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(status),message);
        }
        return envelope.path("data");
    }
}
