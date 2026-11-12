package com.chatchat.mcpserver.market;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.market.storage.*;
import com.chatchat.mcpserver.news.runtime.NewsRuntimeClient;

import com.fasterxml.jackson.databind.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Admin-authenticated fixed operations, called only by the Runtime resource governor.
 * Delegates existing engines; never accepts an index, SQL or arbitrary search DSL. */
@RestController
@RequestMapping("/api/v1/resource-exploration")
public class ResourceExplorationReadController {
    private final NewsRuntimeClient news;
    private final FinancialAssetCatalogService catalog;
    private final FinancialDataStore market;
    private final ObjectMapper json;
    public ResourceExplorationReadController(NewsRuntimeClient news,FinancialAssetCatalogService catalog,FinancialDataStore market,ObjectMapper json) {
        this.news=news;this.catalog=catalog;this.market=market;this.json=json;
    }
    @PostMapping("/read-batch")
    public ApiResponse<List<Map<String,Object>>> readBatch(@RequestBody JsonNode request) throws Exception {
        if(!request.isObject()||request.size()!=1||!request.path("requests").isArray()||request.path("requests").isEmpty()||request.path("requests").size()>16||json.writeValueAsBytes(request).length>160000)
            throw new IllegalArgumentException("Bounded source validation batch required");
        var items=request.path("requests");var newsItems=json.createArrayNode();var positions=new ArrayList<Integer>();
        for(int i=0;i<items.size();i++) {
            var item=items.get(i);String tool=item.path("tool").asText();
            if(!item.isObject()||item.size()!=2||!item.path("arguments").isObject()
                ||!Set.of("news_get_article","market_get_dataset","market_get_history","market_get_indicators","market_get_snapshot","market_resolve_security","market_get_day_snapshot").contains(tool))
                throw new IllegalArgumentException("Invalid source validation operation");
            var allowed=argumentFields(tool);item.path("arguments").fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unknown resource argument");});
            if(tool.equals("news_get_article")){newsItems.add(item);positions.add(i);}
        }
        var result=new ArrayList<Map<String,Object>>(Collections.nCopies(items.size(),null));
        if(!positions.isEmpty()) {
            var response=news.post("/resource-exploration/batch",Map.of("requests",newsItems));
            if(!response.isArray()||response.size()!=positions.size())throw new IllegalStateException("Invalid news validation response");
            for(int i=0;i<positions.size();i++)result.set(positions.get(i),json.convertValue(response.get(i),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        }
        for(int i=0;i<items.size();i++)if(result.get(i)==null)result.set(i,read(items.get(i)).getData());
        if(json.writeValueAsBytes(result).length>10*1024*1024)throw new IllegalArgumentException("Source validation batch exceeds byte budget");
        return ApiResponse.success(result);
    }
    @PostMapping("/read")
    public ApiResponse<Map<String,Object>> read(@RequestBody JsonNode request) throws Exception {
        if(!request.isObject()||request.size()!=2||!request.has("tool")||!request.path("arguments").isObject())throw new IllegalArgumentException("Bounded resource read required");
        String tool=request.path("tool").asText();JsonNode a=request.path("arguments");
        Set<String> allowed=argumentFields(tool);
        a.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown resource argument");});
        Map<String,Object> result;
        if(tool.equals("market_resolve_security")) {
            var items=market.resolveSecurities(text(a,"query",200,true),text(a,"exchange",8,false),number(a,"limit",10,1,20));
            return ApiResponse.success(new LinkedHashMap<>(Map.of("items",items,"count",items.size(),
                "status",items.isEmpty()?"NO_DIRECTORY_MATCH":"CANDIDATES_REQUIRE_QUOTE_VERIFICATION",
                "authority","UNTRUSTED_SOURCE_DATA","coverage","Maintained official security directory; an empty result does not prove absent market observations.")));
        }
        if(tool.equals("market_get_day_snapshot")) {
            String dataset=text(a,"dataset",64,true);var day=date(a,"trade_date");
            if(!a.path("source_id").isIntegralNumber()||!a.path("source_id").canConvertToLong()||a.path("source_id").asLong()<1)
                throw new IllegalArgumentException("Exact source_id required");
            var raw=market.daySnapshot(dataset,day,a.path("source_id").asLong());
            var rows=json.convertValue(raw.get("rows"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            if(!Boolean.TRUE.equals(raw.get("complete")))throw new IllegalStateException("Incomplete daily source snapshot");
            if(rows.stream().anyMatch(row->"true".equalsIgnoreCase(Objects.toString(row.get("legal_risk"),""))))throw new SecurityException("Restricted financial source");
            result=new LinkedHashMap<>();result.put("dataset",dataset);result.put("asset",asset(market.catalog(dataset)));
            result.put("items",rows.stream().map(this::observation).toList());result.put("count",rows.size());result.put("complete",true);
            result.put("trade_date",day.toString());result.put("source_id",a.path("source_id").asLong());result.put("coverage",raw.get("coverage"));
            result.put("storage_tiers",List.of("daily_hot"));result.put("realtime",false);result.put("authority","UNTRUSTED_SOURCE_DATA");
            result.put("delivery","IMMUTABLE_DAY_FILE");
            if(rows.isEmpty()&&((Map<?,?>)result.get("asset")).get("last_observation_date")==null)
                result.put("source_coverage",market.validDatasetCoverage(dataset));
            if(json.writeValueAsBytes(result).length>8*1024*1024)throw new IllegalStateException("DAY_SNAPSHOT_BYTE_LIMIT: no complete daily snapshot delivered");
            return ApiResponse.success(result);
        }
        if(tool.startsWith("news_"))return ApiResponse.success(json.convertValue(news.post("/resource-exploration",request),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        if(tool.equals("market_get_dataset")) {
            result=asset(market.catalog(text(a,"dataset",64,true)));
            if(result.isEmpty())throw new IllegalArgumentException("Dataset unavailable");
        } else if(tool.equals("market_search")) {
            int limit=number(a,"limit",10,1,20),offset=number(a,"offset",0,0,40);
            var found=catalog.searchCallerQuery(text(a,"query",1000,false),50).stream().map(this::asset).toList();
            result=page(found.stream().skip(offset).limit(limit).toList(),offset,limit,found.size(),50);
        } else {
            return readObservations(tool,a);
        }
        result.put("authority","UNTRUSTED_SOURCE_DATA");result.put("license_status","source_declared_or_unknown_not_a_commercial_license_grant");
        if(json.writeValueAsBytes(result).length>145000)throw new IllegalArgumentException("Result exceeds resource budget; narrow the query");
        return ApiResponse.success(result);
    }
    private static Set<String> argumentFields(String tool) {
        return switch(tool) {
            case "news_search","news_related_events"->Set.of("query","security_code","start_time","end_time","industry","limit","offset");
            case "news_get_article"->Set.of("article_id","version_id","offset","limit","view");
            case "market_get_dataset"->Set.of("dataset");
            case "market_search"->Set.of("query","limit","offset");
            case "market_resolve_security"->Set.of("query","exchange","limit");
            case "market_get_day_snapshot"->Set.of("dataset","trade_date","source_id");
            case "market_get_history","market_get_indicators","market_get_snapshot"->Set.of("dataset","filters","start_date","end_date","history_mode","limit","offset");
            default->throw new IllegalArgumentException("Unknown resource operation");
        };
    }
    private ApiResponse<Map<String,Object>> readObservations(String tool,JsonNode a) throws Exception {
            Map<String,Object> result;
            String dataset=text(a,"dataset",64,true);if(!dataset.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid dataset");
            var filters=new LinkedHashMap<String,Object>();
            if(a.has("filters")) {
                if(!a.path("filters").isObject()||a.path("filters").size()>10)throw new IllegalArgumentException("Bounded exact-match filters required");
                a.path("filters").fields().forEachRemaining(e->{if(!e.getKey().matches("[a-z][a-z0-9_]{0,63}")||!e.getValue().isValueNode()||e.getValue().asText().length()>200)throw new IllegalArgumentException("Invalid market filter");filters.put(e.getKey(),json.convertValue(e.getValue(),Object.class));});
            }
            var start=date(a,"start_date");var end=date(a,"end_date");if(start!=null&&end!=null&&start.isAfter(end))throw new IllegalArgumentException("Invalid market date range");
            int limit=number(a,"limit",20,1,50),offset=number(a,"offset",0,0,150);
            String mode=text(a,"history_mode",12,false);if(mode.isBlank())mode="auto";
            if(!Set.of("auto","daily","weekly").contains(mode))throw new IllegalArgumentException("Invalid history tier");
            var metadata=market.catalog(dataset);
            if(metadata.isEmpty())throw new IllegalArgumentException("Unknown financial dataset");
            if(metadata.get("fields") instanceof List<?> fields&&fields.stream().anyMatch(f->f instanceof Map<?,?> field&&"legal_risk".equals(field.get("field_name"))))
                filters.put("legal_risk",false); // Enforced in the existing parameterized SQL before recall.
            var raw=market.query(dataset,filters,start,end,200,mode);
            var rows=json.convertValue(raw.get("rows"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            if(rows.stream().anyMatch(row->"true".equalsIgnoreCase(Objects.toString(row.get("legal_risk"),""))))throw new SecurityException("Restricted financial source returned by storage");
            if(tool.equals("market_get_snapshot")&&!rows.isEmpty()) {
                String latest=Objects.toString(rows.get(0).get("observation_date"),"");
                rows=rows.stream().filter(row->latest.equals(Objects.toString(row.get("observation_date"),""))).toList();
            }
            result=page(rows.stream().skip(offset).limit(limit).map(this::observation).toList(),offset,limit,rows.size(),200);
            result.put("dataset",dataset);result.put("asset",asset(metadata));result.put("history_mode",raw.get("history_mode"));result.put("storage_tiers",raw.get("storage_tiers"));
            result.put("data_as_of",rows.isEmpty()?null:rows.get(0).get("observation_date"));
            result.put("realtime",false);result.put("status","collected_observations_not_live_quotes");
            result.put("unit_policy","Use declared field units; missing units are unknown. Do not infer adjustment or calculation conventions.");
            result.put("market_policy","Market/exchange is per observation when declared by source fields; otherwise unknown.");
            result.put("adjustment_basis","not_declared_unless_present_in_source_fields");
            if(rows.isEmpty())result.put("discovery",Map.of("status","NO_MATCH_IN_REQUESTED_SLICE",
                "next_steps",List.of("Resolve names or qualified codes with market_resolve_security; preserve exchange and instrument type.",
                    "Inspect an unfiltered market_search catalog and actual fields, coverage and source_id before another authorized read.",
                    "For a known source/day, market_get_day_snapshot delivers a bounded immutable file for Python filtering when authorized; this empty slice does not establish platform-wide absence.")));
            // A missing catalog date alone proves nothing. For a candidate empty dataset,
            // ask the existing bounded store about both retained tiers, preserving any failure.
            if(rows.isEmpty()&&metadata.get("last_observation_date")==null)
                result.put("source_coverage",market.validDatasetCoverage(dataset));
            if(tool.equals("market_get_indicators"))result.put("indicator_policy","Returns collected dataset fields; no unrequested derived indicator is computed.");
        result.put("authority","UNTRUSTED_SOURCE_DATA");result.put("license_status","source_declared_or_unknown_not_a_commercial_license_grant");
        if(json.writeValueAsBytes(result).length>145000)throw new IllegalArgumentException("Result exceeds resource budget; narrow the query");
        return ApiResponse.success(result);
    }
    private Map<String,Object> asset(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("dataset_code","asset_name","business_description","business_tags_json","fields","source_names_json","last_observation_date","last_collected_at","update_frequency","history_granularity","hot_retention_days","archive_retention_days"))if(raw.containsKey(key))result.put(key,raw.get(key));
        return result;
    }
    private Map<String,Object> observation(Map<String,Object> raw) {
        var result=new LinkedHashMap<>(raw);result.remove("payload_json");result.remove("id");result.remove("_identity_rank");result.remove("_group_rank");
        result.keySet().removeIf(key->key.toLowerCase(Locale.ROOT).matches(".*(password|secret|credential|cookie|authorization|access_token|refresh_token).*"));
        if(result.containsKey("source_url"))result.put("source_url",safeUrl(Objects.toString(result.get("source_url"),"")));return result;
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
