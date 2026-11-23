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
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
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
        private final String protocolCode,validationFeedback,candidateSha256;
        NodeException(int status){this(status,null,null,null);}
        NodeException(int status,String protocolCode,String validationFeedback,String candidateSha256){super("Native S3 Node rejected request: HTTP "+status);this.status=status;this.protocolCode=protocolCode;this.validationFeedback=validationFeedback;this.candidateSha256=candidateSha256;}
        public int status(){return status;}
        public String protocolCode(){return protocolCode;}
        /** Actual Host validation only; no generated analysis or repair policy. */
        public String validationFeedback(){return validationFeedback;}
        public String candidateSha256(){return candidateSha256;}
    }
    public static final class TransportException extends IllegalStateException {
        TransportException(Throwable cause){super("NATIVE_TRANSPORT_OUTCOME_UNKNOWN",cause);}
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
            var reply=boundedResponse(request,65536,65536);
            {
                var value=json.readTree(reply.body());
                boolean compatible=value!=null&&(value.has("protocolVersion")?
                    value.path("protocolVersion").isIntegralNumber()&&value.path("protocolVersion").intValue()==1
                        &&java.util.stream.StreamSupport.stream(value.path("capabilities").spliterator(),false)
                            .anyMatch(capability->"native-s3-v2".equals(capability.asText())):
                    "4.1.27".equals(value.path("version").asText()));
                if(reply.statusCode()!=200||!compatible||!"cline-standalone".equals(value.path("engine").asText())
                        ||value.path("legacySdkWorker").asBoolean(true))
                    throw new IllegalStateException("Native Core identity or protocol not verified");
                return value;
            }
        }
        catch(IOException unavailable){throw new IllegalStateException("Native identity check unavailable",unavailable);}
    }
    public JsonNode status(String grant,String session){return request(path(session,"status"),grant,null,null);}
    JsonNode stopStatus(String grant,String session,Duration remaining){
        return request(path(session,"status"),grant,null,null,remaining.toMillis());
    }
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
    public JsonNode messageReceipt(String grant,String session,String key){return request(path(session,"message-receipt"),grant,null,key);}
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
        return HttpRequest.newBuilder(endpoint.resolve(path)).header("Authorization","Bearer "+grant).timeout(Duration.ofSeconds(10));
    }
    private JsonNode request(String path,String grant,Object body,String idempotencyKey){
        return request(path,grant,body,idempotencyKey,Long.MAX_VALUE);
    }
    private JsonNode request(String path,String grant,Object body,String idempotencyKey,long remainingMillis){
        var builder=builder(path,grant).header("Accept","application/json");
        if(idempotencyKey!=null){if(idempotencyKey.isBlank()||idempotencyKey.length()>128||idempotencyKey.chars().anyMatch(c->c<32||c>126))throw new IllegalArgumentException("Explicit idempotency key required");builder.header("Idempotency-Key",idempotencyKey);}
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
        var action=path.substring(path.lastIndexOf('/')+1).split("[?]",2)[0];
        long millis=Math.max(1,Math.min(remainingMillis,switch(action){
            case "delivery","delivery-preview","upload" -> 95_000;
            case "recovery-stop" -> 15_000;
            default -> 10_000;
        }));
        if(Set.of("sessions","messages","message-receipt","delivery","delivery-preview","delivery-content","upload","acceptance").contains(action)){
            try{
                var claims=json.readTree(Base64.getUrlDecoder().decode(grant.substring(0,grant.indexOf('.'))));
                if(claims.path("expiresAt").isIntegralNumber())
                    millis=Math.min(millis,Math.max(1,claims.path("expiresAt").asLong()-System.currentTimeMillis()));
            }catch(IOException|IllegalArgumentException ignored){/* Node validates the signed grant. */}
        }
        try{
            var response=boundedResponse(builder.timeout(Duration.ofMillis(millis)).build(),12*1024*1024,32768);
            if(response.statusCode()<200||response.statusCode()>=300){
                String code=null,feedback=null,candidate=null;
                if(response.statusCode()==422)try{
                    var receipt=json.readTree(response.body());
                    String actualCode=receipt==null?"":receipt.path("code").asText();
                    if(Set.of("DELIVERY_SELECTION_INVALID","DELIVERY_CONTENT_INVALID").contains(actualCode)){
                        code=actualCode;feedback=receipt.path("message").asText("")
                            .replaceAll("https?://[^\\s]+","[URL]")
                            .replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [REDACTED]");
                        if(feedback.length()>4096)feedback=feedback.substring(0,4096);
                        var hash=receipt.path("selectionSha256").asText();
                        if(hash.matches("[a-f0-9]{64}"))candidate=hash;
                    }
                }catch(IOException ignored){/* Status remains authoritative. */}
                throw new NodeException(response.statusCode(),code,feedback,candidate);
            }
            var result=json.readTree(response.body());
            if(result==null||!result.isObject())throw new IOException("Expected a Native response object");
            return result;
        }catch(IOException e){throw new TransportException(e);}
    }
    private HttpResponse<byte[]> boundedResponse(HttpRequest request,int successLimit,int errorLimit){
        var pending=http.sendAsync(request,info->new BoundedBody(
            info.statusCode()>=200&&info.statusCode()<300?successLimit:errorLimit));
        try{return pending.get(request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();throw new TransportException(e);}
        catch(ExecutionException|TimeoutException e){pending.cancel(true);throw new TransportException(e);}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long remaining;
        BoundedBody(int limit){remaining=limit;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> data){
            for(var buffer:data)remaining-=buffer.remaining();
            if(remaining<0){subscription.cancel();delegate.onError(new IOException("Native response exceeds resource bound"));return;}
            delegate.onNext(data);
        }
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){delegate.onComplete();}
    }
    private byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(IOException e){throw new IllegalArgumentException("Invalid protocol document",e);}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
