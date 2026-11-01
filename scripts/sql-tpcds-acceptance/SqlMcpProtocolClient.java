import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.McpCallerSignature;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent protocol probe. Uses production credential binding/signing, not business beans or an Agent executor. */
public class SqlMcpProtocolClient {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(InternalCredentialProperties.class)
    static class CredentialConfiguration { }
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String endpoint,token,session,protocol="2025-11-25",secret,tenant,user,agent;
    int sequence;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Provide one private settings JSON path");
        // Configuration binding only: no component scan, auto-configuration, DB connection or migrations.
        try(var context=new SpringApplicationBuilder(CredentialConfiguration.class).web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF).logStartupInfo(false).properties("logging.level.root=OFF").run()) {
            var client=new SqlMcpProtocolClient();
            client.secret=context.getBean(InternalCredentialProperties.class).resolvedSecret();
            client.endpoint=context.getEnvironment().getRequiredProperty("chatchat.mcp.center.base-url").replaceAll("/+$","")+"/mcp";
            client.run(Path.of(args[0]));
        }
    }
    void run(Path settings)throws Exception {
        var config=json.readTree(Files.readString(settings));
        token=json.readTree(Files.readString(Path.of(config.path("authFile").asText()))).path("token").asText();
        var identity=api("/enterprise/auth/me");tenant=identity.path("tenantId").asText();user=identity.path("id").asText();
        if(tenant.isBlank()||user.isBlank())throw new SecurityException("Authenticated canonical identity required");
        agent=config.path("agentId").asText();if(!agent.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid Agent ID");
        var actual=api("/agents/workshop/"+agent);
        if(!agent.equals(actual.path("id").asText()))throw new SecurityException("Authenticated Agent not accessible");
        var initialized=rpc("initialize",Map.of("protocolVersion",protocol,"capabilities",Map.of(),"clientInfo",Map.of("name","runtime-sql-independent-probe","version","1.0")),false);
        if(!initialized.path("response").path("result").has("protocolVersion"))throw new IllegalStateException("MCP initialize failed: HTTP "+initialized.path("httpStatus")+"; "+initialized.path("response").path("message").asText());
        protocol=initialized.path("response").path("result").path("protocolVersion").asText(protocol);
        notifyInitialized();
        var records=new ArrayList<Object>();
        for(var action:config.path("requests")) {
            String method=action.path("method").asText();if(!Set.of("tools/list","tools/call").contains(method))throw new IllegalArgumentException("Only list/call protocol methods supported");
            var params=json.convertValue(action.path("params"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            if("tools/call".equals(method)) {
                String name=Objects.toString(params.get("name"),"");
                // Real MCP authorization is still authoritative; an unbound Agent must be denied by the server.
                if(!name.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid tool name");
            }
            var record=rpc(method,params,true);((com.fasterxml.jackson.databind.node.ObjectNode)record).put("caseId",action.path("caseId").asText());records.add(record);
        }
        var result=new LinkedHashMap<String,Object>();result.put("capturedAt",Instant.now().toString());result.put("clientKind","INDEPENDENT_MCP_PROTOCOL_CLIENT_NOT_AGENT_EXECUTION");
        result.put("identitySource","Authenticated Runtime OS /enterprise/auth/me and accessible Agent configuration");
        result.put("scope",Map.of("tenantId",tenant,"userId",user,"agentId",agent));result.put("initialization",initialized);result.put("protocolVersion",protocol);result.put("records",records);
        Files.writeString(Path.of(config.path("outputFile").asText()),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println(json.writeValueAsString(Map.of("records",records.size(),"clientKind",result.get("clientKind"))));
        if(session!=null)http.send(base().DELETE().build(),HttpResponse.BodyHandlers.discarding());
    }
    JsonNode api(String route)throws Exception {
        var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1"+route)).timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        var j=json.readTree(r.body());if(r.statusCode()!=200||j.path("code").asInt()!=200)throw new SecurityException("Runtime login/Agent check rejected: "+r.statusCode()+"/"+j.path("code").asInt());return j.path("data");
    }
    HttpRequest.Builder base(){
        var b=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json").header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",protocol)
            .header("X-MCP-TOKEN",secret);
        if(session!=null)b.header("Mcp-Session-Id",session);return b;
    }
    void notifyInitialized()throws Exception {
        var r=http.send(base().POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","method","notifications/initialized")))).build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()>=400)throw new IllegalStateException("MCP initialized notification rejected: "+r.statusCode());
    }
    JsonNode rpc(String method,Map<String,Object> input,boolean caller)throws Exception {
        var params=new LinkedHashMap<>(input);String trace=UUID.randomUUID().toString();
        if(caller)params.put("_meta",Map.of(McpCallerSignature.KEY,McpCallerSignature.issue(secret,tenant,user,agent),"traceId",trace));
        int id=++sequence;var request=Map.of("jsonrpc","2.0","id",id,"method",method,"params",params);
        long started=System.nanoTime();var response=http.send(base().header("X-Request-Id",trace).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),HttpResponse.BodyHandlers.ofString());
        session=response.headers().firstValue("Mcp-Session-Id").orElse(session);
        JsonNode value=null;
        if(response.body().stripLeading().startsWith("{"))value=json.readTree(response.body());
        else for(String line:response.body().split("\\r?\\n"))if(line.startsWith("data:")){
            var item=json.readTree(line.substring(5).trim());if(item.path("id").asInt(-1)==id){value=item;break;}
        }
        if(value==null)throw new IllegalStateException("No JSON-RPC response for "+method+"; HTTP "+response.statusCode());
        // Persist only unsigned public request fields; no bearer, HMAC, nonce, endpoint or service credential.
        return json.valueToTree(Map.of("request",Map.of("jsonrpc","2.0","id",id,"method",method,"params",input),"traceId",trace,"httpStatus",response.statusCode(),
            "durationMs",(System.nanoTime()-started)/1000000,"response",value));
    }
}
