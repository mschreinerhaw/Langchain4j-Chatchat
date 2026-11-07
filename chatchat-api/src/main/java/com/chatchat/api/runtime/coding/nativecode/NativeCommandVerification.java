package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.NativeExecutorPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Finite verification identities under the executor-owned cwd; does not parse or rewrite shell commands. */
final class NativeCommandVerification {
    private NativeCommandVerification(){}
    static List<String> hashes(String declared){
        if(declared==null||declared.isBlank())return List.of();
        return List.of(declared,"cd /workspace/code && "+declared,"cd '/workspace/code' && "+declared,"cd \"/workspace/code\" && "+declared)
            .stream().flatMap(command->Stream.of(command,command+" 2>&1"))
            .flatMap(command->Stream.of(NativeCodeWorkspace.sha(command.getBytes(StandardCharsets.UTF_8)),
                CodingCanonicalHash.sha256Value(Map.of("command",command)))).toList();
    }
    static boolean matches(String declared,String actualHash){return hashes(declared).contains(actualHash);}
    /** Bounded output-filter grammar, not an arbitrary shell parser or command rewriter. */
    static boolean eligible(String declared,Object actual){
        if(declared==null||declared.isBlank())return false;
        String text;
        if(actual instanceof String value)text=value;
        else if(actual instanceof Map<?,?> value&&value.get("command") instanceof String command&&Set.of("command","args").containsAll(value.keySet())){
            if(!value.containsKey("args"))text=command;
            else if(value.get("args") instanceof List<?> args){
                if(args.isEmpty())text=command;
                else{
                    if(!command.matches("[-A-Za-z0-9_./:=]+")||args.stream().anyMatch(arg->!(arg instanceof String word)||!word.matches("[-A-Za-z0-9_./:=]+")))return false;
                    text=command+" "+String.join(" ",args.stream().map(Object::toString).toList());
                }
            }else return false;
        }else return false;
        text=text.trim();
        for(String prefix:List.of("","cd /workspace/code && ","cd '/workspace/code' && ","cd \"/workspace/code\" && ")){
            if(!text.startsWith(prefix))continue;
            String candidate=text.substring(prefix.length());
            for(int before=0;before<=3;before++){
                if(candidate.startsWith(declared)){
                    String suffix=candidate.substring(declared.length());
                    int after=suffix.indexOf(" && ");
                    if(after>=0){
                        String[] inspections=suffix.substring(after+4).split(" && ",-1);
                        if(inspections.length>3||java.util.Arrays.stream(inspections).anyMatch(value->!inspection(value)))break;
                        suffix=suffix.substring(0,after);
                    }
                    if(suffix.isEmpty()||suffix.equals(" 2>&1")||suffix.matches("[ \\t]*(?:2>&1[ \\t]*)?\\|[ \\t]*(?:head|tail)[ \\t]+-(?:n[ \\t]+)?[1-9][0-9]{0,3}[ \\t]*"))return true;
                    break;
                }
                int next=candidate.indexOf(" && ");
                if(next<=0||!inspection(candidate.substring(0,next)))break;
                candidate=candidate.substring(next+4);
            }
        }
        return false;
    }
    private static boolean inspection(String value){return value.matches("(?:cat|ls|pwd|md5sum|sha256sum)(?:[ \\t]+[-A-Za-z0-9_./]+)*");}
    static String declarationHash(String declared){return NativeCodeWorkspace.sha(declared.getBytes(StandardCharsets.UTF_8));}
    static boolean certifies(CodingTaskContract c,String declared,JsonNode prepared,JsonNode receipt){
        var policy=NativeExecutorPolicy.from(c);
        if(policy==NativeExecutorPolicy.LITERAL)return matches(declared,prepared.path("commandHash").asText());
        return policy.id().equals(prepared.path("executorPolicy").asText())&&prepared.path("verificationEligible").asBoolean()
            &&declarationHash(declared).equals(prepared.path("verificationDeclarationSha256").asText())
            &&policy.id().equals(receipt.path("verificationPolicy").asText())&&receipt.path("declaredCommandMatched").asBoolean()
            &&declarationHash(declared).equals(receipt.path("declaredCommandSha256").asText());
    }
}
