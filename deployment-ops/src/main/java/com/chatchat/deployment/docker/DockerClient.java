package com.chatchat.deployment.docker;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Docker Engine HTTP over a JDK Unix-domain socket; no Docker CLI or shell. */
public final class DockerClient {
    private final Path socket;
    private final String prefix;
    private final ObjectMapper json=new ObjectMapper();
    public DockerClient(Path socket){this(socket,"/v1.45");}
    public DockerClient(Path socket,String prefix){if(!Set.of("","/v1.45").contains(prefix))throw new IllegalArgumentException("Invalid Unix HTTP API prefix");this.socket=socket;this.prefix=prefix;}
    public JsonNode call(String method,String path)throws IOException{return call(method,path,null);}
    public JsonNode call(String method,String path,JsonNode value)throws IOException{
        byte[] body=value==null?new byte[0]:json.writeValueAsBytes(value);
        try(Response response=request(method,path,new ByteArrayInputStream(body),body.length,"application/json",Duration.ofSeconds(100))){
            byte[] bytes=response.body.readNBytes(32*1024*1024+1);
            if(bytes.length>32*1024*1024)throw new IOException("Docker response exceeds limit");
            if(response.status>=300){String message=new String(bytes,StandardCharsets.UTF_8);throw new IOException("Docker HTTP "+response.status+": "+message.substring(0,Math.min(message.length(),500)));}
            return bytes.length==0?json.createObjectNode():json.readTree(bytes);
        }
    }
    public Response request(String method,String path,InputStream body,long size,String type,Duration timeout)throws IOException{
        if(!Set.of("GET","POST","DELETE","PUT").contains(method)||!path.startsWith("/")||path.contains("\r")||path.contains("\n"))throw new IOException("Invalid Docker request");
        SocketChannel channel=SocketChannel.open(StandardProtocolFamily.UNIX);Deadline io=null;
        try{
            channel.configureBlocking(false);channel.connect(UnixDomainSocketAddress.of(socket));
            io=new Deadline(channel,timeout);while(!channel.finishConnect())io.ready(SelectionKey.OP_CONNECT);
            String headers=method+" "+prefix+path+" HTTP/1.1\r\nHost: docker\r\nConnection: close\r\nContent-Type: "+type+"\r\nContent-Length: "+size+"\r\n\r\n";
            io.write(ByteBuffer.wrap(headers.getBytes(StandardCharsets.US_ASCII)));
            byte[] block=new byte[65536];long remaining=size;
            while(remaining>0){int count=body.read(block,0,(int)Math.min(block.length,remaining));if(count<0)throw new EOFException("Truncated Docker request");io.write(ByteBuffer.wrap(block,0,count));remaining-=count;}
            BufferedInputStream input=new BufferedInputStream(io,65536);String status=line(input);String[] fields=status.split(" ",3);
            if(fields.length<2)throw new IOException("Malformed Docker HTTP status");
            Map<String,String> responseHeaders=new HashMap<>();int total=0;
            for(String h;(h=line(input)).length()>0;){total+=h.length();if(total>65536)throw new IOException("Oversized Docker headers");int colon=h.indexOf(':');if(colon<1)throw new IOException("Malformed Docker header");responseHeaders.put(h.substring(0,colon).toLowerCase(Locale.ROOT),h.substring(colon+1).trim());}
            InputStream payload="chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"))?new Chunked(input):input;
            return new Response(Integer.parseInt(fields[1]),payload,io);
        }catch(Exception error){if(io!=null)io.close();else channel.close();if(error instanceof IOException e)throw e;throw new IOException("Docker request failed",error);}
    }
    private static String line(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;
        for(int c;(c=input.read())!=-1;){if(previous=='\r'&&c=='\n'){byte[] value=bytes.toByteArray();return new String(value,0,value.length-1,StandardCharsets.US_ASCII);}bytes.write(c);previous=c;if(bytes.size()>16384)throw new IOException("Oversized HTTP line");}
        throw new EOFException("Truncated Docker HTTP response");
    }
    public static final class Response implements AutoCloseable {
        public final int status;public final InputStream body;private final Deadline connection;
        Response(int status,InputStream body,Deadline connection){this.status=status;this.body=body;this.connection=connection;}
        public void close()throws IOException{connection.close();}
    }
    private static final class Deadline extends InputStream {
        final SocketChannel channel;final Selector selector;final long end;
        Deadline(SocketChannel channel,Duration timeout)throws IOException{this.channel=channel;selector=Selector.open();channel.register(selector,SelectionKey.OP_READ);end=System.nanoTime()+timeout.toNanos();}
        void ready(int operation)throws IOException{
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Docker operation interrupted");
            long left=end-System.nanoTime();if(left<=0)throw new SocketTimeoutException("Docker operation timed out");
            channel.keyFor(selector).interestOps(operation);selector.select(Math.max(1,left/1_000_000));selector.selectedKeys().clear();
        }
        void write(ByteBuffer bytes)throws IOException{while(bytes.hasRemaining())if(channel.write(bytes)==0)ready(SelectionKey.OP_WRITE);}
        public int read(byte[] bytes,int offset,int length)throws IOException{if(length==0)return 0;ByteBuffer target=ByteBuffer.wrap(bytes,offset,length);for(;;){int count=channel.read(target);if(count!=0)return count;ready(SelectionKey.OP_READ);}}
        public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        public void close()throws IOException{try{channel.close();}finally{selector.close();}}
    }
    private static final class Chunked extends InputStream {
        final InputStream input;long remaining;boolean finished,started;
        Chunked(InputStream input){this.input=input;}
        public int read(byte[] bytes,int offset,int length)throws IOException{
            if(length==0)return 0;if(finished)return -1;
            if(remaining==0){if(started&&!line(input).isEmpty())throw new IOException("Malformed chunk boundary");String header=line(input).split(";",2)[0];try{remaining=Long.parseLong(header,16);}catch(NumberFormatException error){throw new IOException("Invalid chunk size",error);}if(remaining<0)throw new IOException("Negative chunk");started=true;if(remaining==0){finished=true;return -1;}}
            int count=input.read(bytes,offset,(int)Math.min(length,remaining));if(count<0)throw new EOFException("Truncated Docker chunk");remaining-=count;return count;
        }
        public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
    }
}
