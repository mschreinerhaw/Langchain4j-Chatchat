package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.common.runtime.coding.VisualReportMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compiles the task's deliverables into the signed Native host discovery rules. */
final class NativeDeliveryPolicy {
    private NativeDeliveryPolicy() {}

    static List<Map<String,Object>> freeze(List<String> types, Map<String,Object> context) {
        boolean visual=VisualReportMode.active(context);
        var rules=new ArrayList<Map<String,Object>>();
        if(new HashSet<>(types).size()!=types.size())
            throw new IllegalArgumentException("Duplicate Native delivery artifact type");
        for(String type:types){
            boolean visualReport=visual&&"REPORT".equals(type);
            List<String> formats=switch(type){
                case "REPORT" -> visualReport?List.of("html","md"):List.of("md");
                case "RESULT" -> List.of("json");
                case "CHART" -> List.of("png","svg","json");
                case "SOURCE" -> List.of("py","sql","js","ts","java","r","sh");
                case "TEST" -> List.of("py","js","ts","java","json","txt");
                case "AUXILIARY" -> List.of("json","csv","txt","md","css","js","png","svg");
                default -> throw new IllegalArgumentException("Unsupported Native delivery artifact type");
            };
            var companions=visualReport?Map.of(
                "CHART",List.of("png","svg"),
                "AUXILIARY",List.of("css","js","json","csv","txt","jpg","jpeg","webp","gif","woff","woff2"))
                :Map.<String,List<String>>of();
            rules.add(Map.of("directory","output/"+type.toLowerCase(Locale.ROOT),"type",type,"formats",formats,
                "minCount",1,"maxCount",List.of("REPORT","RESULT").contains(type)?1:1000,"htmlCompanions",companions));
        }
        return List.copyOf(rules);
    }
    static boolean accepts(JsonNode rules,JsonNode artifacts) {
        if(!rules.isArray()||rules.isEmpty()||!artifacts.isArray()||artifacts.isEmpty()||artifacts.size()>1000)return false;
        var claimed=new HashSet<String>();
        for(var rule:rules){
            String directory=rule.path("directory").asText()+"/";
            int primary=0;
            boolean html=false,companion=false;
            for(var artifact:artifacts){
                String path=artifact.path("path").asText();
                if(!path.startsWith(directory))continue;
                String type=artifact.path("type").asText();
                String extension=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                if(type.equals(rule.path("type").asText())&&contains(rule.path("formats"),extension)){
                    primary++;
                    html|="html".equals(extension);
                }else if(contains(rule.path("htmlCompanions").path(type),extension)){
                    companion=true;
                }else return false;
                if(!claimed.add(path))return false;
            }
            if(primary<rule.path("minCount").asInt()||primary>rule.path("maxCount").asInt()||companion&&!html)return false;
        }
        return claimed.size()==artifacts.size();
    }

    private static boolean contains(JsonNode values,String value) {
        for(var item:values)if(value.equals(item.asText()))return true;
        return false;
    }
}
