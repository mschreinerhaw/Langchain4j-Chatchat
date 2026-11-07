package com.chatchat.common.runtime.coding;

/** Approved execution semantics are frozen by the host, never supplied in model tool arguments. */
public enum NativeExecutorPolicy {
    LITERAL("LITERAL_COMMAND_V1", "runtime-coding:native-project:v1", "native_command.mjs"),
    PIPEFAIL("BASH_PIPEFAIL_V1", "runtime-coding:native-project:v2", "native_command_pipefail.mjs");
    private final String id,profile,helper;
    NativeExecutorPolicy(String id,String profile,String helper){this.id=id;this.profile=profile;this.helper=helper;}
    public String id(){return id;}
    public String profile(){return profile;}
    public String helper(){return helper;}
    public String helperSha256(){
        try(var stream=NativeExecutorPolicy.class.getResourceAsStream("/python/"+helper)){
            if(stream==null)throw new SecurityException("Approved native executor resource unavailable");
            return NativeCodeWorkspace.sha(stream.readAllBytes());
        }catch(java.io.IOException invalid){throw new IllegalStateException("Cannot pin native executor resource",invalid);}
    }
    public String capability(){return "NATIVE_EXECUTOR:"+id+":"+helperSha256()+":"+LITERAL.helperSha256();}
    public static NativeExecutorPolicy from(CodingTaskContract c){
        if(c.metadataRefs().contains(LITERAL.profile)&&c.metadataRefs().contains(PIPEFAIL.profile))throw new SecurityException("Ambiguous native executor profile");
        var policy=c.metadataRefs().contains(PIPEFAIL.profile)?PIPEFAIL:LITERAL;
        if(!c.metadataRefs().contains(policy.profile))throw new SecurityException("Native executor profile unavailable");
        Object selected=c.runtimeContext().get("nativeExecutionPolicy");
        if(policy==PIPEFAIL&&!policy.id.equals(selected)||selected!=null&&!policy.id.equals(selected))
            throw new SecurityException("Native executor policy differs from frozen profile");
        return policy;
    }
}
