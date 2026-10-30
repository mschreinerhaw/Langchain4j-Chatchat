package com.chatchat.api.datascience.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source delivery evidence. This manifest never certifies database execution or business metrics. */
public final class SqlStudioDelivery {
    private SqlStudioDelivery() { }
    /** Bounded, exact replacement hunk; full revisions remain in their operation receipts. */
    public static List<Map<String,Object>> changes(List<SqlOutputBundle.Candidate> previous,List<SqlOutputBundle.Candidate> current){
        var before=new LinkedHashMap<String,String>();previous.forEach(file->before.put(file.path(),file.content()));
        var after=new LinkedHashMap<String,String>();current.forEach(file->after.put(file.path(),file.content()));
        var paths=new LinkedHashSet<>(before.keySet());paths.addAll(after.keySet());
        var changes=new ArrayList<Map<String,Object>>();
        for(String path:paths){
            String old=before.getOrDefault(path,""),fresh=after.getOrDefault(path,"");if(old.equals(fresh))continue;
            int start=0,oldEnd=old.length(),newEnd=fresh.length();
            while(start<oldEnd&&start<newEnd&&old.charAt(start)==fresh.charAt(start))start++;
            while(oldEnd>start&&newEnd>start&&old.charAt(oldEnd-1)==fresh.charAt(newEnd-1)){oldEnd--;newEnd--;}
            String removed=old.substring(start,oldEnd),added=fresh.substring(start,newEnd);
            changes.add(Map.of("path",path,"beforeSha256",sha(old),"afterSha256",sha(fresh),"offset",start,
                "removed",removed.substring(0,Math.min(removed.length(),4096)),"added",added.substring(0,Math.min(added.length(),4096)),
                "complete",removed.length()<=4096&&added.length()<=4096));
        }
        return List.copyOf(changes);
    }
    public static Map<String,Object> manifest(String dialect,List<SqlOutputBundle.Candidate> files,
            Map<String,Object> validation,Map<String,Object> metadata) {
        if(files.isEmpty() || !Boolean.TRUE.equals(validation.get("valid")))
            throw new IllegalArgumentException("A SQL delivery requires validated nonempty source files");
        var references=validation.getOrDefault("references",List.of());
        boolean physicalReferences=references instanceof List<?> list&&!list.isEmpty();
        if(physicalReferences&&!"CHECKED".equals(validation.get("fieldValidation")))
            throw new IllegalArgumentException("Referenced SQL objects require field validation before delivery");
        var artifacts=files.stream().map(file->Map.<String,Object>of("path",file.path(),"sha256",sha(file.content()),
            "bytes",file.content().getBytes(StandardCharsets.UTF_8).length,"state","SAVED_FOR_REVIEW")).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion","sql-studio.delivery.v1");result.put("targetDialect",dialect);
        result.put("files",artifacts);result.put("metadata",metadata);
        result.put("referencedObjects",references);result.put("referencedColumns",validation.getOrDefault("referencedColumns",List.of()));
        result.put("relationships",validation.getOrDefault("relationships",List.of()));
        result.put("unresolvedIssues",validation.getOrDefault("structuralDiagnostics",List.of()));
        result.put("validation",Map.of("syntax","PARSED","fields",validation.getOrDefault("fieldValidation","NOT_EVALUATED"),
            "businessSemantics","NOT_EVALUATED","parser",validation.getOrDefault("parser","sqlglot"),"version",validation.getOrDefault("version","UNKNOWN")));
        result.put("execution",Map.of("state","SKIPPED","sqlExecuted",false,"reasonCode","SOURCE_GENERATION_ONLY_NO_TARGET_EXECUTION_AUTHORITY",
            "reason","本任务仅生成目标方言 SQL；已授权数据源仅提供元数据，未配置并授权目标数据库试执行。"));
        result.put("stages",List.of(Map.of("stage","METADATA_DISCOVERY","state",physicalReferences?"COMPLETED":"NO_PHYSICAL_REFERENCES"),
            Map.of("stage","SQL_GENERATION","state","COMPLETED"),Map.of("stage","SQL_VALIDATION","state","COMPLETED"),
            Map.of("stage","SQL_EXECUTION","state","SKIPPED"),Map.of("stage","SQL_REPAIR","state","SEE_OPERATION_RECEIPTS"),
            Map.of("stage","ARTIFACT_DELIVERY","state","SAVED_FOR_REVIEW")));
        return Collections.unmodifiableMap(result);
    }
    private static String sha(String content){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
