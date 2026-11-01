package com.chatchat.mcpserver.sql.template;

import java.util.*;
import java.util.regex.Pattern;

/** Server-created JDBC plan for governed Studio templates. JSON arguments cannot instantiate this type. */
public record StudioPreparedSql(String sql,List<Object> values,List<String> names,Map<String,Object> effectiveParameters) {
    public static final String KEY="__studioPreparedSql";
    private static final Pattern NAME=Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    public StudioPreparedSql {
        values=Collections.unmodifiableList(new ArrayList<>(values)); // null is a legitimate JDBC binding.
        names=List.copyOf(names);effectiveParameters=Collections.unmodifiableMap(new LinkedHashMap<>(effectiveParameters));
    }
    public static StudioPreparedSql compile(String source,Map<String,Object> parameters) {
        var sql=new StringBuilder();var values=new ArrayList<Object>();var names=new ArrayList<String>();
        for(int i=0;i<source.length();) {
            char c=source.charAt(i);
            if(c=='#'||source.startsWith("--",i)) {int end=source.indexOf('\n',i);if(end<0)end=source.length();sql.append(source,i,end);i=end;continue;}
            if(source.startsWith("/*",i)){int end=source.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Unclosed SQL comment");end+=2;sql.append(source,i,end);i=end;continue;}
            if(c=='\''||c=='"'||c=='`') {
                int end=i+1;boolean closed=false;
                while(end<source.length()) {
                    if(source.charAt(end)=='\\'){end+=2;continue;}
                    if(source.charAt(end)==c){if(end+1<source.length()&&source.charAt(end+1)==c){end+=2;continue;}closed=true;break;}end++;
                }
                if(!closed)throw new IllegalArgumentException("Unclosed quoted SQL value");
                String body=source.substring(i+1,end);
                if(body.contains("{{")) {
                    if(c=='`'||!body.startsWith("{{")||!body.endsWith("}}")||body.indexOf("{{",2)>=0)
                        throw new IllegalArgumentException("SQL parameters must represent complete values, not identifiers or embedded SQL text");
                    bind(body.substring(2,body.length()-2).trim(),parameters,sql,values,names);
                }else sql.append(source,i,end+1);
                i=end+1;continue;
            }
            if(source.startsWith("{{",i)) {int end=source.indexOf("}}",i+2);if(end<0)throw new IllegalArgumentException("Unclosed SQL parameter");bind(source.substring(i+2,end).trim(),parameters,sql,values,names);i=end+2;continue;}
            sql.append(c);i++;
        }
        return new StudioPreparedSql(sql.toString(),values,names,parameters);
    }
    private static void bind(String name,Map<String,Object> parameters,StringBuilder sql,List<Object> values,List<String> names) {
        if(!NAME.matcher(name).matches()||!parameters.containsKey(name))throw new IllegalArgumentException("Missing or invalid SQL parameter: "+name);
        Object value=parameters.get(name);
        if(value instanceof Collection<?> items) {
            if(items.isEmpty()){sql.append("NULL");return;}
            int index=0;for(Object item:items){if(index++>0)sql.append(", ");scalar(name,item,sql,values,names);}return;
        }
        scalar(name,value,sql,values,names);
    }
    private static void scalar(String name,Object value,StringBuilder sql,List<Object> values,List<String> names) {
        if(value!=null && !(value instanceof String||value instanceof Number||value instanceof Boolean))throw new IllegalArgumentException("SQL parameter must be scalar: "+name);
        if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))throw new IllegalArgumentException("Non-finite SQL parameter: "+name);
        sql.append('?');values.add(value);names.add(name);
    }
}
