package com.chatchat.api.runtime.coding.cline;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Backend selection/identity guard only. Runtime's existing facade retains all governance. */
public final class ClineNativeCodingAdapter implements CodingAgentSPI {
    private final CodingAgentSPI delegate;
    private final URI node;
    private final String secret;
    private final ObjectMapper json;
    public ClineNativeCodingAdapter(CodingAgentSPI delegate, URI node, String secret, ObjectMapper json) {
        this.delegate=delegate;this.node=node;this.secret=secret;this.json=json;
    }
    private void nativeBackend() {
        try {
            var request=HttpRequest.newBuilder(node.resolve("/healthz")).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+secret).GET().build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            var body=json.readTree(response.body());
            if(response.statusCode()!=200||!"cline-standalone".equals(body.path("engine").asText())
                    ||!"4.1.26".equals(body.path("version").asText())||body.path("legacySdkWorker").asBoolean(true))
                throw new IllegalStateException("Native backend identity not verified; no legacy fallback");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native backend check interrupted",interrupted);}
        catch(java.io.IOException invalid){throw new IllegalStateException("Native backend unavailable; no legacy fallback",invalid);}
    }
    public CodingTaskOutcome submit(CodingTaskContract task){nativeBackend();return delegate.submit(task);}
    public CodingTaskOutcome get(KernelDataScope scope,String task){return delegate.get(scope,task);}
    public CodingTaskOutcome cancel(KernelDataScope scope,String task,String reason){return delegate.cancel(scope,task,reason);}
    public CodingTaskOutcome resume(KernelDataScope scope,String task){nativeBackend();return delegate.resume(scope,task);}
    public Subscription subscribe(KernelDataScope scope,String task,long sequence,Observer observer){return delegate.subscribe(scope,task,sequence,observer);}
}
