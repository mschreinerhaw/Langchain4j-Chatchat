package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.security.InternalRequestSigner;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.web.server.ResponseStatusException;

/** Real child Worker and signed HTTP callbacks. Owns and stops only its child process. */
final class CodingNodeHttpFixture implements AutoCloseable {
    final String secret="0123456789abcdef0123456789abcdef";
    final ObjectMapper json;final HttpServer callback;final ExecutorService threads=Executors.newCachedThreadPool();
    Process worker;final int port;volatile Consumer<JsonNode> afterCheckpoint=ignored->{};
    final Map<String,java.util.concurrent.atomic.AtomicInteger> callbackCounts=new ConcurrentHashMap<>();
    CodingNodeHttpFixture(ObjectMapper json,ClineModelCallbackController model,ClinePythonCallbackController python) throws Exception {
        this.json=json;callback=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);callback.setExecutor(threads);
        try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        callback.createContext("/internal/coding/cline/",exchange->{
            int status=200;Object reply;
            try {
                byte[] bytes=exchange.getRequestBody().readAllBytes();var h=exchange.getRequestHeaders();
                String expected=InternalRequestSigner.signBody(secret,"POST",exchange.getRequestURI().getPath(),h.getFirst(InternalRequestSigner.TIMESTAMP_HEADER),h.getFirst(InternalRequestSigner.NONCE_HEADER),bytes);
                if(!InternalRequestSigner.matches(h.getFirst("Authorization"),"Bearer "+secret)||!InternalRequestSigner.matches(expected,h.getFirst(InternalRequestSigner.SIGNATURE_HEADER)))throw new SecurityException("Signature mismatch");
                var body=json.readTree(bytes);String action=exchange.getRequestURI().getPath().substring(23);
                callbackCounts.computeIfAbsent(action,ignored->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                reply=switch(action) {
                    case "reserve-model"->model.reserve(body);case "model"->model.model(body);case "settle-model"->model.settle(body);
                    case "event"->model.event(body);case "finish"->model.finish(body);case "data-request"->model.dataRequest(body);
                    case "validate-sql"->model.validateSql(body);
                    case "checkpoint"->{var saved=model.checkpoint(body);afterCheckpoint.accept(body.path("checkpoint"));yield saved;}
                    case "prepare-python"->python.prepare(body);case "execute-python"->python.execute(body);case "get-python"->python.get(body);case "cancel-python"->python.cancel(body);
                    default->throw new IllegalArgumentException("Unexpected callback "+action);
                };
            }catch(ResponseStatusException e){e.printStackTrace();status=e.getStatusCode().value();reply=Map.of("message",Objects.toString(e.getReason(),"Rejected"));}
            catch(Exception e){status=500;reply=Map.of("message",e.toString());}
            try {byte[] bytes=json.writeValueAsBytes(reply);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
            finally{exchange.close();}
        });
        callback.start();startWorker();
    }
    URI uri(){return URI.create("http://127.0.0.1:"+port);}
    JsonNode state(com.chatchat.common.runtime.coding.CodingTaskContract contract,long fence) throws Exception {
        var body=Map.of("scope",contract.scope(),"taskId",contract.taskId(),"contractHash",com.chatchat.common.runtime.coding.CodingCanonicalHash.sha256(contract),"fencingToken",fence);
        var reply=HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri().resolve("/v1/runs/get")).header("Authorization","Bearer "+secret)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString());
        return json.readTree(reply.body());
    }
    void startWorker() throws Exception {
        String module=Path.of("..","coding-agent-node","src","server.js").toAbsolutePath().normalize().toUri().toString();
        String script="import {createCodingServer} from '"+module+"';createCodingServer({secret:'"+secret+"',callbackUrl:'http://127.0.0.1:"+callback.getAddress().getPort()+"'}).listen("+port+",'127.0.0.1');";
        var builder=new ProcessBuilder(System.getProperty("coding.node","node"),"--input-type=module","-e",script).redirectErrorStream(true);
        builder.environment().put("CLINE_NATIVE_SDK_ENABLED",System.getProperty("coding.nativeSdk","true"));
        worker=builder.start();
        var http=HttpClient.newHttpClient();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until) {
            try{if(http.send(HttpRequest.newBuilder(uri().resolve("/healthz")).header("Authorization","Bearer "+secret).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}
            catch(Exception waiting){if(!worker.isAlive())throw new IllegalStateException("Worker exited: "+log());}
            Thread.sleep(25);
        }
        throw new IllegalStateException("Worker did not start: "+log());
    }
    String log() throws java.io.IOException {return new String(worker.getInputStream().readNBytes(Math.min(worker.getInputStream().available(),8192)),java.nio.charset.StandardCharsets.UTF_8);}
    void stopWorker() throws Exception {
        if(worker!=null){worker.descendants().forEach(ProcessHandle::destroyForcibly);worker.destroyForcibly();worker.waitFor(5,TimeUnit.SECONDS);worker.getInputStream().close();worker.getErrorStream().close();worker.getOutputStream().close();worker=null;}
    }
    public void close() throws Exception {try{stopWorker();}finally{callback.stop(0);threads.shutdownNow();}}
}
