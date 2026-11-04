package com.chatchat.runtime.news.api.tool;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import com.chatchat.runtime.news.model.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
/** Signed internal read adapter over the existing news engine. No new index or collector. */
@RestController @RequestMapping("/internal/v1/news/resource-exploration")
public class NewsResourceReadController {
    private final NewsDocumentStore news;private final ObjectMapper json;
    public NewsResourceReadController(NewsDocumentStore news,ObjectMapper json){this.news=news;this.json=json;}
    @PostMapping("/batch")
    public ApiResponse<List<Map<String,Object>>> readBatch(@RequestBody JsonNode request) throws Exception {
        if(!request.isObject()||request.size()!=1||!request.path("requests").isArray()||request.path("requests").isEmpty()||request.path("requests").size()>16||json.writeValueAsBytes(request).length>160000)
            throw new IllegalArgumentException("Bounded article validation batch required");
        for(var item:request.path("requests")) {
            if(!item.isObject()||item.size()!=2||!item.path("arguments").isObject()||!item.path("tool").asText().equals("news_get_article"))
                throw new IllegalArgumentException("Article validation operations only");
            item.path("arguments").fieldNames().forEachRemaining(key->{if(!Set.of("article_id","version_id","offset","limit","view").contains(key))throw new IllegalArgumentException("Unknown news argument");});
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var item:request.path("requests"))result.add(read(item).getData());
        if(json.writeValueAsBytes(result).length>2500000)throw new IllegalArgumentException("Article validation batch exceeds byte budget");
        return ApiResponse.success(result);
    }
    @PostMapping
    public ApiResponse<Map<String,Object>> read(@RequestBody JsonNode request) throws Exception {
        if(!request.isObject()||request.size()!=2||!request.has("tool")||!request.path("arguments").isObject())throw new IllegalArgumentException("Bounded news read required");
        String tool=request.path("tool").asText();JsonNode a=request.path("arguments");
        Set<String> allowed=switch(tool){case "news_search","news_related_events"->Set.of("query","security_code","start_time","end_time","industry","limit","offset");case "news_get_article"->Set.of("article_id","version_id","offset","limit","view");default->throw new IllegalArgumentException("Unknown news operation");};
        a.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown news argument");});
        Map<String,Object> result;
        if(tool.equals("news_get_article")) {
            var article=news.findById(text(a,"article_id",256,true)).orElseThrow(()->new IllegalArgumentException("Article unavailable"));
            String view=text(a,"view",16,false);if(!view.isBlank()&&!Set.of("body","summary").contains(view))throw new IllegalArgumentException("Invalid article view");
            rejectRestricted(article);var item=article(article,!view.equals("summary"),number(a,"offset",0,0,200000),number(a,"limit",6000,200,12000));
            if(!item.get("version_id").equals(text(a,"version_id",64,true)))throw new IllegalArgumentException("Article version changed; search again");
            result=new LinkedHashMap<>(item);
        } else {
            String query=text(a,"query",1000,false),code=text(a,"security_code",24,false);
            if(!code.isBlank()&&!code.matches("[A-Za-z0-9.:-]{1,24}"))throw new IllegalArgumentException("Invalid security code");
            var from=instant(a,"start_time");var to=instant(a,"end_time");
            if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("Invalid publication time range");
            String industry=text(a,"industry",100,false);int limit=number(a,"limit",5,1,10),offset=number(a,"offset",0,0,40);
            // Existing engine owns lexical/semantic ranking, daily-index selection and temporal filtering.
            var found=news.search(new NewsSearchQuery(query,List.of(),from,to,List.of(),50,code,true,industry));
            var items=found.stream().skip(offset).limit(limit).map(d->article(d,false,0,1000)).toList();
            result=page(items,offset,limit,found.size(),50);result.put("time_filter","publishTime");
            result.put("security_association",code.isBlank()?"not_requested":"indexed_securityCode_or_stockList_only");
            result.put("industry_association",industry.isBlank()?"not_requested":"lexical_source_text_filter_not_a_curated_industry_taxonomy");
            if(tool.equals("news_related_events"))result.put("relationship_status","candidate_articles_not_verified_events");
        }
        result.put("authority","UNTRUSTED_SOURCE_DATA");result.put("license_status","source_declared_or_unknown_not_a_commercial_license_grant");
        if(json.writeValueAsBytes(result).length>145000)throw new IllegalArgumentException("Result exceeds resource budget");
        return ApiResponse.success(result);
    }
    private void rejectRestricted(NewsDocument d) {
        if(d.metadata()!=null&&Boolean.TRUE.equals(d.metadata().get("legalRisk")))throw new SecurityException("Source is restricted by collection compliance metadata");
    }
    private Map<String,Object> article(NewsDocument d,boolean full,int offset,int count) {
        rejectRestricted(d);var item=new LinkedHashMap<String,Object>();String content=Objects.toString(d.content(),"");
        item.put("article_id",d.documentId());item.put("title",d.title());item.put("source_name",d.sourceName());item.put("source_url",safeUrl(d.sourceUrl()));
        item.put("published_at",d.publishTime());item.put("collected_at",d.collectTime());item.put("categories",d.categories());item.put("tags",d.tags());
        item.put("version_id",hash(List.of(Objects.toString(d.title(),""),content,Objects.toString(d.summary(),""),Objects.toString(d.publishTime(),""),Objects.toString(d.sourceUrl(),""))));
        String text=full?content:Objects.toString(d.summary(),content);int end=Math.min(text.length(),Math.min(offset+count,200000));
        item.put("text",offset>=text.length()?"":text.substring(offset,end));item.put("offset",offset);item.put("next_offset",end<text.length()&&end<200000?end:null);item.put("truncated",end<text.length());
        item.put("source_view",full?"body":"summary");
        var metadata=d.metadata()==null?Map.<String,Object>of():d.metadata();var fields=new LinkedHashMap<String,Object>();
        for(String key:List.of("securityCode","securityName","stockList","provider","legalDisclaimer","legalRisk","documentKind","parentDocumentId","chunkIndex"))if(metadata.containsKey(key))fields.put(key,metadata.get(key));
        item.put("source_metadata",fields);return item;
    }
    private static Map<String,Object> page(List<?> items,int offset,int limit,int found,int ceiling) {
        var value=new LinkedHashMap<String,Object>();value.put("items",items);value.put("count",items.size());value.put("offset",offset);
        value.put("next_offset",offset+limit<found?offset+limit:null);value.put("window_limit",ceiling);value.put("window_may_be_truncated",found>=ceiling);
        value.put("pagination_consistency","current_bounded_window; data updates may reorder results; deduplicate by source identity and narrow the time range");return value;
    }
    private String hash(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeUrl(String value){try{var u=java.net.URI.create(value);return Set.of("https","http").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null?value:"";}catch(RuntimeException invalid){return "";}}
    private static String text(JsonNode a,String key,int max,boolean required) {var n=a.get(key);if(n==null&&!required)return "";if(n==null||!n.isTextual()||n.asText().length()>max||required&&n.asText().isBlank())throw new IllegalArgumentException("Invalid "+key);return n.asText().trim();}
    private static int number(JsonNode a,String key,int fallback,int min,int max){var n=a.get(key);if(n==null)return fallback;if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<min||n.asInt()>max)throw new IllegalArgumentException("Invalid "+key);return n.asInt();}
    private static Instant instant(JsonNode a,String key){String s=text(a,key,40,false);return s.isBlank()?null:Instant.parse(s);}
    private static LocalDate date(JsonNode a,String key){String s=text(a,key,10,false);return s.isBlank()?null:LocalDate.parse(s);}
}
