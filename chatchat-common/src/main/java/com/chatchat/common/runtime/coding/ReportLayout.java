package com.chatchat.common.runtime.coding;

import java.util.*;

/** Closed, data-free layout vocabulary shared with the Vue renderer. */
public final class ReportLayout {
    public static final String VERSION="report_layout.v1";
    public static final Set<String> TYPES=Set.of("summary","metric","section","chart","table","limitations","sources");
    private ReportLayout() { }
    public static Map<String,Object> validate(Object raw,Map<String,Set<String>> available){
        require(raw instanceof Map<?,?>,"Layout must be an object");var layout=(Map<?,?>)raw;
        require(layout.keySet().equals(Set.of("schemaVersion","blocks"))&&VERSION.equals(layout.get("schemaVersion")),"Unknown layout schema");
        require(layout.get("blocks") instanceof List<?> list&&list.size()<=256,"Layout block limit");
        var result=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();var placements=new HashSet<String>();
        for(Object value:(List<?>)layout.get("blocks")){
            require(value instanceof Map<?,?>,"Layout block required");var block=(Map<?,?>)value;
            require(Set.of("id","type","ref","span","emphasis").containsAll(block.keySet()),"Unknown layout field");
            require(block.get("id") instanceof String id&&id.matches("[A-Za-z0-9_-]{1,64}")&&ids.add(id),"Invalid/duplicate layout id");
            require(block.get("type") instanceof String type&&TYPES.contains(type),"Unknown layout component");
            String type=(String)block.get("type");
            if(available.containsKey(type))require(block.get("ref") instanceof String ref&&available.get(type).contains(ref),"Unknown report element reference");
            else require(!block.containsKey("ref"),"Non-reference block cannot carry ref");
            require(placements.add(type+":"+Objects.toString(block.get("ref"),"")),"Duplicate report placement");
            if(block.containsKey("span"))require(block.get("span") instanceof String span&&Set.of("full","half").contains(span),"Invalid layout span");
            if(block.containsKey("emphasis"))require(block.get("emphasis") instanceof String emphasis&&Set.of("normal","important").contains(emphasis),"Invalid layout emphasis");
            var clean=new LinkedHashMap<String,Object>();block.forEach((key,item)->clean.put((String)key,item));result.add(Map.copyOf(clean));
        }
        return Map.of("schemaVersion",VERSION,"blocks",List.copyOf(result));
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
