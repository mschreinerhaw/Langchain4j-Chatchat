package com.chatchat.runtime.news.store.opensearch;

import com.chatchat.common.retrieval.query.SearchQueryOptimizer;

import com.chatchat.common.retrieval.query.QueryOptimizationException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/** Reuses the supplied registered transport; cancellation reaches the actual HTTP request. */
public final class NewsBudgetedOpenSearchRequests {
    private NewsBudgetedOpenSearchRequests() { }
    public static Map<String,Object> search(SearchQueryOptimizer optimizer, RestClient client, ObjectMapper mapper,
                                            String endpoint, Map<String,Object> body) throws Exception {
        return optimizer.search(body, SearchQueryOptimizer.Mode.TEXT, (prepared, timeout) ->
            request(optimizer, client, mapper, endpoint, mapper.writeValueAsString(prepared), "application/json", timeout));
    }
    public static Map<String,Object> request(SearchQueryOptimizer optimizer, RestClient client, ObjectMapper mapper,
        String endpoint, String body, String contentType, int timeout) throws Exception {
        if (body.getBytes(StandardCharsets.UTF_8).length > optimizer.budget().getMaxRequestBytes())
            throw new QueryOptimizationException("QUERY_REQUEST_BUDGET_EXCEEDED", "Serialized search request exceeds byte budget");
        Request request = new Request("POST", endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint);
        if (endpoint.contains("?")) for (String pair : endpoint.substring(endpoint.indexOf('?')+1).split("&")) {
            String[] part = pair.split("=", 2); request.addParameter(part[0], part.length == 2 ? part[1] : "");
        }
        request.setEntity(new StringEntity(body, ContentType.create(contentType, StandardCharsets.UTF_8)));
        var future = new CompletableFuture<Response>();
        Cancellable pending = client.performRequestAsync(request, new ResponseListener() {
            public void onSuccess(Response response) { future.complete(response); }
            public void onFailure(Exception exception) { future.completeExceptionally(exception); }
        });
        try {
            Response response = future.get(Math.max(1, timeout), TimeUnit.MILLISECONDS);
            try (var stream = response.getEntity().getContent()) {
                return mapper.readValue(stream, new TypeReference<Map<String,Object>>() {});
            }
        } catch (TimeoutException expired) {
            pending.cancel(); throw new QueryOptimizationException("QUERY_TIMEOUT", "OpenSearch HTTP deadline exceeded");
        } catch (InterruptedException interrupted) {
            pending.cancel(); Thread.currentThread().interrupt();
            throw new QueryOptimizationException("QUERY_CANCELLED", "OpenSearch HTTP request cancelled");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw failed;
        }
    }
}
