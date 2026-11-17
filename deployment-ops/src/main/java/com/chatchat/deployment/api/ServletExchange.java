package com.chatchat.deployment.api;

import com.sun.net.httpserver.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.*;
import java.util.*;

/** Streams the existing audited API through Spring's servlet server in the same JVM. */
final class ServletExchange extends HttpExchange {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
    private final Map<String,Object> attributes = new HashMap<>();
    private int status = 200;
    ServletExchange(HttpServletRequest request, HttpServletResponse response) {
        this.request=request; this.response=response;
        for (String name : Collections.list(request.getHeaderNames()))
            requestHeaders.put(name, Collections.list(request.getHeaders(name)));
    }
    public Headers getRequestHeaders(){return requestHeaders;}
    public Headers getResponseHeaders(){return responseHeaders;}
    public URI getRequestURI(){return URI.create(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));}
    public String getRequestMethod(){return request.getMethod();}
    public HttpContext getHttpContext(){return null;}
    public void close(){try{response.flushBuffer();}catch(IOException ignored){}}
    public InputStream getRequestBody(){try{return request.getInputStream();}catch(IOException e){throw new UncheckedIOException(e);}}
    public OutputStream getResponseBody(){try{return response.getOutputStream();}catch(IOException e){throw new UncheckedIOException(e);}}
    public void sendResponseHeaders(int code,long length){
        status=code;response.setStatus(code);
        responseHeaders.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));
        if(length>0)response.setContentLengthLong(length);
    }
    public InetSocketAddress getRemoteAddress(){return new InetSocketAddress(request.getRemoteAddr(),request.getRemotePort());}
    public InetSocketAddress getLocalAddress(){return new InetSocketAddress(request.getLocalAddr(),request.getLocalPort());}
    public int getResponseCode(){return status;}
    public String getProtocol(){return request.getProtocol();}
    public Object getAttribute(String name){return attributes.get(name);}
    public void setAttribute(String name,Object value){attributes.put(name,value);}
    public void setStreams(InputStream input,OutputStream output){throw new UnsupportedOperationException();}
    public HttpPrincipal getPrincipal(){return null;}
}
