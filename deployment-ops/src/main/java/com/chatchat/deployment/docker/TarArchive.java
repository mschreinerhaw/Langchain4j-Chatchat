package com.chatchat.deployment.docker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded TAR metadata reader and regular-file injector; never extracts host paths. */
public final class TarArchive {
    private TarArchive(){}
    public static byte[] entry(Path archive,String wanted,int maximum)throws IOException{
        try(InputStream file=Files.newInputStream(archive);BufferedInputStream buffered=new BufferedInputStream(file,65536)){
            buffered.mark(2);int first=buffered.read(),second=buffered.read();buffered.reset();InputStream input=first==31&&second==139?new GZIPInputStream(buffered,65536):buffered;
            byte[] header=new byte[512];int entries=0;
            for(;;){int count=input.readNBytes(header,0,512);if(count==0)break;if(count!=512)throw new EOFException("Truncated TAR header");boolean zero=true;for(byte value:header)zero&=value==0;if(zero)break;
                if(++entries>100000)throw new IOException("Too many TAR members");long size=number(header,124,12);if(size<0)throw new IOException("Invalid TAR size");
                String name=text(header,0,100),prefix=text(header,345,155);if(!prefix.isEmpty())name=prefix+"/"+name;
                long sum=0;for(int i=0;i<512;i++)sum+=i>=148&&i<156?32:header[i]&255;if(sum!=number(header,148,8))throw new IOException("TAR header checksum mismatch");
                if(name.equals(wanted)){if(header[156]!=0&&header[156]!='0')throw new IOException("Regular TAR metadata required");if(size>maximum)throw new IOException("Oversized TAR metadata");byte[] value=input.readNBytes((int)size);if(value.length!=size)throw new EOFException("Truncated TAR metadata");return value;}
                input.skipNBytes(size);input.skipNBytes((512-size%512)%512);
            }
        }throw new FileNotFoundException("TAR entry missing: "+wanted);
    }
    private static String text(byte[] bytes,int start,int length){int end=start;while(end<start+length&&bytes[end]!=0)end++;return new String(bytes,start,end-start,StandardCharsets.UTF_8);}
    private static long number(byte[] bytes,int start,int length)throws IOException{String value=text(bytes,start,length).trim();try{return value.isEmpty()?0:Long.parseLong(value,8);}catch(NumberFormatException error){throw new IOException("Unsupported TAR numeric encoding",error);}}
    private static void text(byte[] header,int offset,int maximum,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>maximum)throw new IOException("TAR field exceeds limit");System.arraycopy(bytes,0,header,offset,bytes.length);}
    private static void octal(byte[] header,int offset,int length,long value)throws IOException{String number=Long.toOctalString(value);if(value<0||number.length()>length-1)throw new IOException("TAR numeric field exceeds limit");text(header,offset,length-1,"0".repeat(length-1-number.length())+number);}
    public static byte[] singleFile(String path,byte[] bytes,int mode,int uid,int gid)throws IOException{
        String name=path.startsWith("/")?path.substring(1):path;if(name.isBlank()||Arrays.asList(name.split("/")).contains(".."))throw new IOException("Unsafe container config target");
        byte[] header=new byte[512];String prefix="";if(name.getBytes(StandardCharsets.UTF_8).length>100){int slash=name.lastIndexOf('/');if(slash<0)throw new IOException("TAR filename too long");prefix=name.substring(0,slash);name=name.substring(slash+1);}
        text(header,0,100,name);text(header,345,155,prefix);octal(header,100,8,mode);octal(header,108,8,uid);octal(header,116,8,gid);octal(header,124,12,bytes.length);octal(header,136,12,0);Arrays.fill(header,148,156,(byte)32);header[156]='0';text(header,257,6,"ustar");text(header,263,2,"00");long sum=0;for(byte value:header)sum+=value&255;octal(header,148,7,sum);header[155]=32;
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);out.write(bytes);out.write(new byte[(512-bytes.length%512)%512+1024]);return out.toByteArray();
    }
}
