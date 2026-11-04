package com.chatchat.runtime.news.internet;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.*;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** Public HTTP only. The TLS host stays unchanged while the connection DNS is pinned. */
@Component
public class PublicWebHttp {
    public static final int MAX_BYTES=1024*1024;
    public static final String USER_AGENT="RuntimeOS-Research/1.0";
    private static final ScheduledThreadPoolExecutor CLOCK=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"public-web-deadline");t.setDaemon(true);return t;});
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"public-web-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static { CLOCK.setRemoveOnCancelPolicy(true); }
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    public record Response(URI url,int status,String contentType,String location,byte[] body) { }
    public static URI validate(String value){
        try{
            if(value==null||value.length()>4096)throw new Rejected("URL_INVALID");
            URI uri=URI.create(value);
            if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
                ||uri.getRawFragment()!=null||uri.getHost().endsWith(".")||uri.getPort()!=-1&&uri.getPort()!=80&&uri.getPort()!=443)
                throw new Rejected("URL_NOT_PUBLIC_WEB");
            return uri;
        }catch(Rejected e){throw e;}catch(RuntimeException e){throw new Rejected("URL_INVALID");}
    }
    public static boolean publicAddress(InetAddress a){
        if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
        byte[] b=a.getAddress();int x=b[0]&255,y=b[1]&255;
        if(b.length==4)return !(x==0||x==10||x==127||x>=224||x==100&&y>=64&&y<=127
            ||x==169&&y==254||x==172&&y>=16&&y<=31||x==192&&(y==168||y==0&&((b[2]&255)==0||(b[2]&255)==2)||y==88&&(b[2]&255)==99)
            ||x==198&&(y==18||y==19||y==51&&(b[2]&255)==100)||x==203&&y==0&&(b[2]&255)==113);
        return (x&0xe0)==0x20 && !(x==0x20&&y==0x02) && !(x==0x3f&&y>=0xfe)
            && !(x==0x20&&y==0x01&&((b[2]&255)<2||(b[2]&255)==0x0d&&(b[3]&255)==0xb8));
    }
    protected InetAddress[] resolve(String host,int millis){
        Future<InetAddress[]> f;
        try{f=DNS.submit(()->InetAddress.getAllByName(host));}catch(RejectedExecutionException e){throw new Rejected("DNS_CAPACITY");}
        try{return f.get(millis,TimeUnit.MILLISECONDS);}catch(Exception e){f.cancel(true);DNS.remove((Runnable)f);if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Rejected("DNS_UNAVAILABLE");}
    }
    public Response request(URI uri,String method,Map<String,String> headers,byte[] body,long deadline){
        validate(uri.toString());int remaining=remaining(deadline);
        InetAddress[] addresses=resolve(uri.getHost(),remaining);
        if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw new Rejected("SSRF_TARGET_DENIED");
        var factories=RegistryBuilder.<ConnectionSocketFactory>create().register("http",PlainConnectionSocketFactory.getSocketFactory())
            .register("https",SSLConnectionSocketFactory.getSystemSocketFactory()).build();
        try(var manager=new PoolingHttpClientConnectionManager(factories,host->{
            if(!host.equalsIgnoreCase(uri.getHost()))throw new UnknownHostException("Unpinned target");return addresses.clone();})){
        manager.setDefaultConnectionConfig(org.apache.http.config.ConnectionConfig.custom().setMessageConstraints(
            org.apache.http.config.MessageConstraints.custom().setMaxHeaderCount(100).setMaxLineLength(8192).build()).build());
        HttpRequestBase request=new HttpRequestBase(){public String getMethod(){return method;}};
        if(body!=null){var post=new HttpPost(uri);post.setEntity(new ByteArrayEntity(body));request=post;}
        else request.setURI(uri);
        request.setConfig(RequestConfig.custom().setConnectTimeout(remaining(deadline)).setSocketTimeout(remaining(deadline))
            .setConnectionRequestTimeout(remaining(deadline)).setRedirectsEnabled(false).build());
        request.setHeader("User-Agent",USER_AGENT);request.setHeader("Accept-Encoding","gzip");
        headers.forEach(request::setHeader);
        final HttpRequestBase pending=request;
        var expiry=CLOCK.schedule(pending::abort,remaining(deadline),TimeUnit.MILLISECONDS);
        try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableContentCompression().build();var response=client.execute(request)){
            byte[] bytes=new byte[0];var entity=response.getEntity();
            if(entity!=null){
                if(entity.getContentLength()>MAX_BYTES)throw new Rejected("RESPONSE_TOO_LARGE");
                try(InputStream raw=bounded(entity.getContent());InputStream decoded="gzip".equalsIgnoreCase(header(response,"Content-Encoding"))?new GZIPInputStream(raw):raw){
                    bytes=decoded.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new Rejected("RESPONSE_TOO_LARGE");
                }
            }
            remaining(deadline);
            return new Response(uri,response.getStatusLine().getStatusCode(),header(response,"Content-Type"),header(response,"Location"),bytes);
        }catch(Rejected e){throw e;}catch(Exception e){if(Thread.currentThread().isInterrupted())throw new Rejected("REQUEST_CANCELLED");throw new Rejected(System.nanoTime()>=deadline?"HTTP_TIMEOUT":"HTTP_UNAVAILABLE");}
        finally{expiry.cancel(false);}
        }
    }
    private static InputStream bounded(InputStream source){return new FilterInputStream(source){int used;
        public int read()throws IOException{int n=super.read();if(n>=0&&++used>MAX_BYTES)throw new Rejected("RESPONSE_TOO_LARGE");return n;}
        public int read(byte[] b,int off,int length)throws IOException{int n=in.read(b,off,Math.min(length,MAX_BYTES-used+1));if(n>0&&(used+=n)>MAX_BYTES)throw new Rejected("RESPONSE_TOO_LARGE");return n;}
    };}
    private static String header(org.apache.http.HttpResponse r,String key){var h=r.getFirstHeader(key);return h==null?"":h.getValue();}
    static int remaining(long deadline){long value=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(value<1)throw new Rejected("HTTP_TIMEOUT");return (int)Math.min(value,30000);}
    public Response get(String url,long deadline){
        URI uri=validate(url);
        for(int i=0;i<=3;i++){
            var response=request(uri,"GET",Map.of(),null,deadline);
            if(response.status()<300||response.status()>399)return response;
            if(response.location().isBlank())throw new Rejected("REDIRECT_INVALID");
            URI next=validate(uri.resolve(response.location()).toString());
            if("https".equals(uri.getScheme())&&!"https".equals(next.getScheme()))throw new Rejected("REDIRECT_DOWNGRADE");
            uri=next;
        }
        throw new Rejected("REDIRECT_LIMIT");
    }
}
