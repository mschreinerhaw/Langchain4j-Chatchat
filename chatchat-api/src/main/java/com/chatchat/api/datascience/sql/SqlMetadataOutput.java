package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Adapts final presentation formats only. All proposals still pass the existing evidence/SQL guards. */
final class SqlMetadataOutput {
    private SqlMetadataOutput() { }
    static final class Invalid extends IllegalArgumentException {
        final String code;
        Invalid(String code,String message){super(message);this.code=code;}
    }
    static Map<String,Object> normalize(String raw,ObjectMapper json){
        String text=Objects.toString(raw,"").strip().replaceFirst("^\\uFEFF","");
        if(text.isBlank())throw new Invalid("OUTPUT_EMPTY","模型没有返回可用内容，请重试或更换模型。");
        String object=text;
        var jsonFence=Pattern.compile("(?is)^```json\\s*\\R?(.*?)\\R?```\\s*$").matcher(text);
        if(jsonFence.matches())object=jsonFence.group(1).strip();
        if(object.startsWith("{")||object.startsWith("[")||text.toLowerCase(Locale.ROOT).startsWith("```json")){
            try{
                var root=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(object);
                if(root==null||!root.isObject())throw new Invalid("OUTPUT_SCHEMA_MISMATCH","模型返回的数据结构不符合 SQL 结果协议，请重试。");
                var result=json.convertValue(root,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
                if(result.containsKey("sql")){
                    if(!(result.get("sql") instanceof String source)||source.isBlank()||result.containsKey("files"))
                        throw new Invalid("OUTPUT_SCHEMA_MISMATCH","SQL 字段为空或与文件提案冲突，请重试。");
                    if(result.containsKey("executionStatus")&&!Set.of("PROPOSED","NOT_EXECUTED").contains(result.get("executionStatus")))
                        throw new Invalid("OUTPUT_SCHEMA_MISMATCH","模型不能声明 SQL 已执行；结果尚未写入编辑器。");
                    result.put("files",List.of(Map.of("path","sql/proposed.sql","content",source)));
                    result.putIfAbsent("notes",result.getOrDefault("explanation","SQL 提案已生成，尚未执行。"));
                }
                if(result.containsKey("schemaVersion")&&!"sql-studio.output.v1".equals(result.get("schemaVersion")))
                    throw new Invalid("OUTPUT_SCHEMA_MISMATCH","模型返回了不支持的 SQL 结果版本，请重试。");
                if(!(result.get("notes") instanceof String)||result.containsKey("files")&&!(result.get("files") instanceof List<?>)
                    ||result.containsKey("candidateTables")&&!(result.get("candidateTables") instanceof List<?>))
                    throw new Invalid("OUTPUT_SCHEMA_MISMATCH","模型结果缺少说明或文件列表格式错误，请重试。");
                result.putIfAbsent("files",List.of());result.putIfAbsent("candidateTables",List.of());
                return result;
            }catch(JsonProcessingException invalid){
                boolean truncated=invalid instanceof JsonEOFException;
                throw new Invalid(truncated?"OUTPUT_JSON_TRUNCATED":"OUTPUT_JSON_INVALID",truncated?
                    "模型结果在 JSON 结构结束前中断，尚未生成可用 SQL；请重试或缩短需求。":"模型返回的 JSON 格式损坏，尚未生成可用 SQL；请重试或更换模型。");
            }
        }
        var files=new ArrayList<Map<String,Object>>();
        var sql=Pattern.compile("(?im)^```(?:sql|mysql|postgresql|postgres|hive|spark|tsql|sqlite)\\s*\\R([\\s\\S]*?)^```\\s*$").matcher(text);
        while(sql.find())files.add(Map.of("path","sql/proposed"+(files.isEmpty()?"":"-"+(files.size()+1))+".sql","content",sql.group(1).strip()));
        if(files.isEmpty()&&Pattern.compile("(?is)^(?:--[^\\n]*\\n|/\\*.*?\\*/\\s*)*(?:SELECT|WITH)\\b.*").matcher(text).matches())
            files.add(Map.of("path","sql/proposed.sql","content",text));
        if(text.contains("```")&&files.isEmpty())throw new Invalid("OUTPUT_FENCE_INVALID","模型代码块未完整结束或格式不受支持，请重试；内容尚未写入编辑器。");
        return new LinkedHashMap<>(Map.of("notes",files.isEmpty()?text:"SQL 提案已生成，尚未执行。"+(text.startsWith("```")||files.get(0).get("content").equals(text)?"":"\n\n"+text),
            "files",files,"candidateTables",List.of()));
    }

    static String diagnosticPreview(String raw){
        String text=Objects.toString(raw,"");
        text=text.replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)","[REDACTED PRIVATE KEY]")
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [REDACTED]")
            .replaceAll("(?i)((?:password|passwd|api[-_ ]?key|token|secret|authorization)[\\\"'\\s]*[:=][\\\"'\\s]*)([^\\s,;\\\"'}]+)","$1[REDACTED]")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[REDACTED EMAIL]")
            .replaceAll("(?<![A-Za-z0-9_])\\d{6,}(?![A-Za-z0-9_])","[REDACTED NUMBER]")
            .replaceAll("'[^'\\r\\n]*'","'[REDACTED LITERAL]'");
        return text.substring(0,Math.min(8000,text.length()));
    }
}
