package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;

/** Unit transport fixtures only; never used as real-model acceptance evidence. */
public final class ClineNativeAdapterProbe {
    static final class Delegate implements CodingAgentSPI {
        int submissions,resumes,reads,cancels;
        public CodingTaskOutcome submit(CodingTaskContract c){submissions++;return null;}
        public CodingTaskOutcome resume(KernelDataScope s,String t){resumes++;return null;}
        public CodingTaskOutcome get(KernelDataScope s,String t){reads++;return null;}
        public CodingTaskOutcome cancel(KernelDataScope s,String t,String reason){cancels++;return null;}
        public Subscription subscribe(KernelDataScope s,String t,long n,Observer o){return ()->{};}
    }
    static void check(boolean ok){if(!ok)throw new AssertionError("Native backend isolation guard failed");}
    static void rejected(Runnable action){try{action.run();throw new AssertionError("Unexpected legacy fallback");}catch(IllegalStateException expected){}}
    public static void main(String[] args)throws Exception {
        String secret="unit-runtime-secret-with-32-characters";
        var body=new AtomicReference<>("{\"sdk\":\"@cline/agents\"}");
        var status=new AtomicInteger(200);var signed=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/healthz",exchange->{
            if(("Bearer "+secret).equals(exchange.getRequestHeaders().getFirst("Authorization")))signed.incrementAndGet();
            byte[] data=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),data.length);
            exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        var delegate=new Delegate();var adapter=new ClineNativeCodingAdapter(delegate,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),secret,new ObjectMapper());
        try {
            rejected(()->adapter.submit(null));check(delegate.submissions==0);
            body.set("{\"engine\":\"cline-standalone\",\"version\":\"4.1.25\",\"legacySdkWorker\":false}");
            rejected(()->adapter.resume(null,"task"));check(delegate.resumes==0);
            body.set("{\"engine\":\"cline-standalone\",\"version\":\"4.1.26\"}");
            rejected(()->adapter.submit(null));check(delegate.submissions==0);
            body.set("{\"engine\":\"cline-standalone\",\"version\":\"4.1.26\",\"legacySdkWorker\":false}");status.set(503);
            rejected(()->adapter.submit(null));check(delegate.submissions==0);
            status.set(200);adapter.submit(null);adapter.resume(null,"task");check(delegate.submissions==1&&delegate.resumes==1&&signed.get()==6);
        }finally{server.stop(0);}
        adapter.get(null,"task");adapter.cancel(null,"task","cancel while native backend is offline");
        check(delegate.reads==1&&delegate.cancels==1);
        System.out.println("Native adapter guard: 7 checks PASS (unit fixtures; no business execution)");
    }
}
