package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private index over existing scoped code artifacts; never a model editing protocol. */
public final class NativeCodeWorkspace {
    private NativeCodeWorkspace() { }
    public static final String PROFILE="runtime-coding:native-project:v1";
    public static final String VERSION="runtime_os.native_code_tree.v1";
    public static final Set<String> TOOLS=Set.of("read_files","search_codebase","editor","apply_patch","run_commands","skills");
    /** A published MCP name is admitted only by an explicit immutable task catalog. */
    public static Set<String> toolNames(CodingTaskContract task){
        var names=new HashSet<>(TOOLS);
        if(task.runtimeContext().get("nativeMcp") instanceof List<?> cards)for(Object value:cards){
            if(!(value instanceof Map<?,?> card)||!(card.get("agentToolName") instanceof String name)
                    ||!name.matches("[a-zA-Z0-9_-]{1,64}")||!(card.get("schemaSha256") instanceof String hash)||!hash.matches("[a-f0-9]{64}")
                    ||!(card.get("serverName") instanceof String server)||server.isBlank()||!(card.get("name") instanceof String remote)||remote.isBlank()
                    ||!(card.get("agentId") instanceof String agent)||agent.isBlank()||!names.add(name))
                throw new SecurityException("Invalid frozen native MCP catalog");
        }
        return Set.copyOf(names);
    }
    public static boolean enabled(CodingTaskContract task){return task!=null&&task.metadataRefs()!=null
        &&(task.metadataRefs().contains(PROFILE)||task.metadataRefs().contains(NativeExecutorPolicy.PIPEFAIL.profile()));}
    public static String path(String value){
        if(value==null||value.length()>160||!value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))throw new SecurityException("CODE_PATH_DENIED");
        for(String part:value.split("/"))if(Set.of(".","..",".git").contains(part.toLowerCase(Locale.ROOT))||part.endsWith(".")
                ||part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))throw new SecurityException("CODE_PATH_DENIED");
        return value;
    }
    public static Map<String,String> files(Map<String,String> input){
        if(input==null||input.size()>32)throw new IllegalArgumentException("Code file count exceeds contract");
        var files=new TreeMap<String,String>();var folded=new HashSet<String>();long bytes=0;
        for(var entry:input.entrySet()){
            String name=path(entry.getKey()),source=entry.getValue();
            if(source==null||source.indexOf(0)>=0||!source.equals(new String(source.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Invalid UTF-8 text source");
            if(!folded.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Case-colliding code paths");
            bytes+=source.getBytes(StandardCharsets.UTF_8).length;files.put(name,source);
        }
        if(bytes>256000)throw new IllegalArgumentException("Code bytes exceed contract");
        return Collections.unmodifiableMap(files);
    }
    public static Map<String,Object> manifest(KernelDataScope scope,String task,Map<String,String> values,long version,String parent){
        if(version<0||version>64||version==0&&parent!=null||version>0&&(parent==null||!parent.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid code lineage");
        var normalized=files(values);var descriptors=new ArrayList<Map<String,Object>>();
        normalized.forEach((path,source)->descriptors.add(Map.of("path",path,"sha256",sha(source.getBytes(StandardCharsets.UTF_8)),
            "bytes",source.getBytes(StandardCharsets.UTF_8).length)));
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",VERSION);body.put("hashAlgorithm",CodingCanonicalHash.ALGORITHM);
        body.put("taskId",task);body.put("scopeBinding",CodingCanonicalHash.sha256Value(List.of(scope.tenantId(),scope.userId(),scope.runId())));
        body.put("version",version);body.put("parent",parent);body.put("files",descriptors);
        String digest=CodingCanonicalHash.sha256Value(body);body.put("sha256",digest);return Collections.unmodifiableMap(body);
    }
    public static Map<String,Object> verify(KernelDataScope scope,String task,Map<String,Object> supplied,Map<String,String> files){
        if(supplied==null||!supplied.keySet().equals(Set.of("schemaVersion","hashAlgorithm","taskId","scopeBinding","version","parent","files","sha256"))
                ||!(supplied.get("version") instanceof Number number))throw new SecurityException("Invalid code manifest");
        var expected=manifest(scope,task,files,number.longValue(),(String)supplied.get("parent"));
        var body=new LinkedHashMap<>(supplied);body.remove("sha256");
        if(!expected.get("sha256").equals(supplied.get("sha256"))||!expected.get("sha256").equals(CodingCanonicalHash.sha256Value(body)))
            throw new SecurityException("CODE_SNAPSHOT_HASH_MISMATCH");
        return expected;
    }
    public static String sha(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
