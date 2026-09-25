package com.chatchat.integration.agent;

import org.a2aproject.sdk.client.http.A2AHttpClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Map;

/** Adds operator-approved query values after the SDK has assembled the final operation URL. */
final class AgentQueryParameterHttpClient implements A2AHttpClient {
    private final A2AHttpClient delegate;
    private final Map<String, String> parameters;

    AgentQueryParameterHttpClient(A2AHttpClient delegate, Map<String, String> parameters) {
        this.delegate = delegate;
        this.parameters = parameters;
    }

    @Override public GetBuilder createGet() { return wrap(delegate.createGet(), GetBuilder.class); }
    @Override public PostBuilder createPost() { return wrap(delegate.createPost(), PostBuilder.class); }
    @Override public DeleteBuilder createDelete() { return wrap(delegate.createDelete(), DeleteBuilder.class); }

    @SuppressWarnings("unchecked")
    private <T> T wrap(T builder, Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object[] effective = args;
            if ("url".equals(method.getName()) && args != null && args.length == 1) {
                effective = new Object[]{AgentRequestParameters.withQuery(URI.create((String) args[0]),
                    parameters).toString()};
            }
            try {
                Object result = method.invoke(builder, effective);
                return result == builder ? proxy : result;
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        });
    }
}
