package com.chatchat.api.runtime.coding.nativecline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Business-neutral S3/Cline protocol client. The authenticated Runtime must
 * authorize snapshots, select the model/tools, persist the task fence and verify
 * deliveries before calling acceptance. This client grants no business access
 * and is deliberately not registered as the production Coding SPI by default.
 */
public final class NativeS3NodeClient {
    public record Input(String path,long size,String sha256,String objectVersion,String url) { }
    public static final class NodeException extends RuntimeException {
        private final int status;
        private final String protocolCode,validationFeedback;
        NodeException(int status){this(status,null,null);}
        NodeException(int status,String protocolCode,String validationFeedback){super("Native S3 Node rejected request: HTTP "+status);this.status=status;this.protocolCode=protocolCode;this.validationFeedback=validationFeedback;}
        public int status(){return status;}
        public String protocolCode(){return protocolCode;}
        /** Actual Host validation only; no generated analysis or repair policy. */
        public String validationFeedback(){return validationFeedback;}
    }
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json;
    public NativeS3NodeClient(URI endpoint,ObjectMapper json){
        if(endpoint==null||!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null
                ||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null
                ||!(endpoint.getPath().isEmpty()||endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("Explicit HTTP Node origin required");
        this.endpoint=URI.create(endpoint.getScheme()+"://"+endpoint.getRawAuthority());this.json=json;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** Same byte representation as Node inputManifestHash, without expiring
     * signatures; the exact S3 object path and immutable version remain bound.
     */
    public String inputManifestHash(List<Input> inputs){
        var canonical=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var input:inputs.stream().sorted(Comparator.comparing(Input::path)).toList()){
            if(!seen.add(input.path()))throw new IllegalArgumentException("Duplicate input path");
            var item=new LinkedHashMap<String,Object>();item.put("path",input.path());item.put("size",input.size());
            item.put("sha256",input.sha256());item.put("objectVersion",input.objectVersion());
            item.put("objectPath",URI.create(URI.create(input.url()).toASCIIString()).getRawPath());canonical.add(item);
        }
        return hash(bytes(canonical));
    }
    /** Only a server-held issuer key may sign a grant after existing Runtime
     * authorization. Never expose this key, signed URLs or grant to Cline.
     */
    public String signGrant(Map<String,Object> grant,String issuerKey){
        if(issuerKey==null||issuerKey.length()<32)throw new IllegalArgumentException("Private issuer key required");
        try{
            String body=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(grant));
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(issuerKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException failure){throw new IllegalStateException("Grant signing unavailable",failure);}
    }
    public JsonNode create(String grant,List<Input> inputs,String idempotencyKey){return request("/v1/sessions",grant,Map.of("inputs",inputs),idempotencyKey);}
    public void requireNativeIdentity(){health();}
    public JsonNode health(){
        try{
            var request=HttpRequest.newBuilder(endpoint.resolve("/health/live")).timeout(Duration.ofSeconds(5)).GET().build();
            var reply=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=reply.body()){
                byte[] bytes=body.readNBytes(65537);if(bytes.length>65536)throw new IllegalStateException("Native identity response exceeds limit");
                var value=json.readTree(bytes);
                if(reply.statusCode()!=200||!"cline-standalone".equals(value.path("engine").asText())||!"4.1.26".equals(value.path("version").asText())||value.path("legacySdkWorker").asBoolean(true))throw new IllegalStateException("Native Core identity not verified; no legacy fallback");
                return value;
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native identity check interrupted",interrupted);}
        catch(IOException unavailable){throw new IllegalStateException("Native identity check unavailable",unavailable);}
    }
    public JsonNode status(String grant,String session){return request(path(session,"status"),grant,null,null);}
    public JsonNode recoveryStop(String grant,String session){return request(path(session,"recovery-stop"),grant,Map.of(),null);}
    public JsonNode observations(String grant,String session,long after,int limit){
        if(after<0||limit<1||limit>256)throw new IllegalArgumentException("Bounded Native event cursor required");
        return request(path(session,"observations")+"?afterEventId="+after+"&limit="+limit,grant,null,null);
    }
    public JsonNode receipts(String grant,String session,long after,int limit){
        if(after<0||limit<1||limit>256)throw new IllegalArgumentException("Bounded provider receipt cursor required");
        return request(path(session,"receipts")+"?afterEventId="+after+"&limit="+limit,grant,null,null);
    }
    public JsonNode executions(String grant,String session){return request(path(session,"executions"),grant,null,null);}
    public byte[] executionContent(String grant,String session,String recordId,String stream,String sourcePath,long size,String sha256){
        if(size<0||size>64L*1024*1024||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Bounded immutable execution content required");
        var output=new java.io.ByteArrayOutputStream();long offset=0;
        while(offset<size){
            var body=new LinkedHashMap<String,Object>();body.put("recordId",recordId);body.put("stream",stream);body.put("offset",offset);body.put("limit",Math.min(8L*1024*1024,size-offset));
            if(sourcePath!=null)body.put("path",sourcePath);
            var reply=request(path(session,"execution-content"),grant,body,null);byte[] part=Base64.getDecoder().decode(reply.path("data").asText());
            if(reply.path("offset").asLong(-1)!=offset||reply.path("totalSize").asLong(-1)!=size||part.length==0||part.length!=reply.path("size").asLong(-1)||!hash(part).equals(reply.path("sha256").asText()))throw new IllegalStateException("Execution chunk integrity differs");
            output.writeBytes(part);offset+=part.length;
        }
        byte[] data=output.toByteArray();if(data.length!=size||!hash(data).equals(sha256))throw new IllegalStateException("Execution evidence SHA-256 differs");return data;
    }
    public JsonNode message(String grant,String session,String text,String idempotencyKey){return request(path(session,"messages"),grant,Map.of("text",text),idempotencyKey);}
    public JsonNode cancel(String grant,String session){return request(path(session,"cancel"),grant,Map.of(),null);}
    public JsonNode preview(String grant,String session,String version){return request(path(session,"delivery-preview"),grant,Map.of("deliveryVersion",version),null);}
    public byte[] previewContent(String grant,String session,JsonNode preview,String artifactPath){
        var metadata=java.util.stream.StreamSupport.stream(preview.path("artifacts").spliterator(),false)
            .filter(a->artifactPath.equals(a.path("path").asText())).findFirst().orElseThrow(()->new IllegalArgumentException("Artifact not in candidate inventory"));
        var reply=request(path(session,"delivery-content"),grant,Map.of("deliveryVersion",preview.path("deliveryVersion").asText(),"path",artifactPath),null);
        byte[] content=Base64.getDecoder().decode(reply.path("data").asText());
        if(!artifactPath.equals(reply.path("path").asText())||content.length!=metadata.path("size").asLong(-1)
                ||!hash(content).equals(metadata.path("sha256").asText()))throw new IllegalStateException("Candidate artifact integrity differs");
        return content;
    }
    public JsonNode seal(String grant,String session,String version){return request(path(session,"delivery"),grant,Map.of("deliveryVersion",version),null);}
    public JsonNode upload(String grant,String session,JsonNode plan){return request(path(session,"upload"),grant,plan,null);}
    public JsonNode accept(String grant,String session,String manifestSha256,boolean passed,String validationRef){
        return request(path(session,"acceptance"),grant,Map.of("manifestSha256",manifestSha256,"status",passed?"PASSED":"FAILED","validationRef",validationRef),null);
    }
    /** Caller owns this stream. SSE event ids support Last-Event-ID replay. */
    public InputStream events(String grant,String session,String lastEventId){
        var builder=builder(path(session,"events"),grant).header("Accept","text/event-stream");
        if(lastEventId!=null){if(!lastEventId.matches("[0-9]+"))throw new IllegalArgumentException("Invalid SSE cursor");builder.header("Last-Event-ID",lastEventId);}
        try{
            var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200){response.body().close();throw new NodeException(response.statusCode());}
            return response.body();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Native SSE interrupted",e);}
        catch(IOException e){throw new IllegalStateException("Native SSE unavailable",e);}
    }
    private static String path(String session,String action){
        if(session==null||!session.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("Invalid task session");
        return "/v1/sessions/"+session+"/"+action;
    }
    private HttpRequest.Builder builder(String path,String grant){
        if(grant==null||grant.length()>16384||!grant.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Signed task grant required");
        return HttpRequest.newBuilder(endpoint.resolve(path)).header("Authorization","Bearer "+grant).timeout(Duration.ofSeconds(300));
    }
    private JsonNode request(String path,String grant,Object body,String idempotencyKey){
        var builder=builder(path,grant).header("Accept","application/json");
        if(idempotencyKey!=null){if(idempotencyKey.isBlank()||idempotencyKey.length()>128||idempotencyKey.chars().anyMatch(c->c<32||c>126))throw new IllegalArgumentException("Explicit idempotency key required");builder.header("Idempotency-Key",idempotencyKey);}
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
        try{
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()){
                if(response.statusCode()<200||response.statusCode()>=300){
                    String code=null,feedback=null;byte[] rejected=input.readNBytes(32769);
                    if(response.statusCode()==422&&rejected.length<=32768){
                        try{
                            var receipt=json.readTree(rejected);String actualCode=receipt.path("code").asText();
                            if(Set.of("DELIVERY_SELECTION_INVALID","DELIVERY_CONTENT_INVALID").contains(actualCode)){
                                code=actualCode;feedback=receipt.path("message").asText("")
                                    .replaceAll("https?://[^\\s\\\"']+","[URL]")
                                    .replaceAll("(?i)Bearer\\s+[^\\s\\\"']+","Bearer [REDACTED]");
                                if(feedback.length()>4096)feedback=feedback.substring(0,4096);
                            }
                        }catch(IOException ignored){/* Status remains authoritative when no valid receipt is available. */}
                    }
                    throw new NodeException(response.statusCode(),code,feedback);
                }
                byte[] data=input.readNBytes(12*1024*1024+1);
                if(data.length>12*1024*1024)throw new IllegalStateException("Native response exceeds resource bound");
                return json.readTree(data);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Native request interrupted; reconcile task before retry",e);}
        catch(IOException e){throw new IllegalStateException("Native outcome unknown; reconcile task before retry",e);}
    }
    private byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(IOException e){throw new IllegalArgumentException("Invalid protocol document",e);}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
