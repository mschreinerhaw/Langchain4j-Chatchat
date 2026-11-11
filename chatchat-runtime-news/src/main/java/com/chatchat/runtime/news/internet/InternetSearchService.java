package com.chatchat.runtime.news.internet;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.search.provider.TencentWebSearchClient;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.jsoup.Jsoup;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;

/** Atomic search/fetch only. Query choice, iteration, source assessment and synthesis belong to Cline. */
@Service
public class InternetSearchService {
    private final InternetProviderStore store;private final PublicWebHttp http;private final RobotsPolicy robots;
    private final ObjectMapper json;private final TencentWebSearchClient signer;
    public InternetSearchService(InternetProviderStore store,PublicWebHttp http,RobotsPolicy robots,ObjectMapper json,TencentWebSearchClient signer){this.store=store;this.http=http;this.robots=robots;this.json=json;this.signer=signer;}
    public Map<String,Object> execute(JsonNode request){
        if(request==null||!request.isObject()||!request.path("arguments").isObject()||request.toString().length()>12000)
            throw new IllegalArgumentException("Bounded Internet tool call required");
        if(!Set.of("providerId","providerRevision","taskId","tenantId","userId","agentId","tool","requestId","arguments").equals(json.convertValue(request,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}).keySet()))throw new IllegalArgumentException("Unknown Internet request field");
        String id=text(request,"providerId",64),tool=text(request,"tool",32),task=text(request,"taskId",256),tenant=text(request,"tenantId",128),user=text(request,"userId",128),agent=text(request,"agentId",256),call=text(request,"requestId",128);
        var p=store.find(id);if(p==null||!p.enabled()||p.credentialCipher()==null)return error("PROVIDER_DISABLED");
        if(p.revision()!=request.path("providerRevision").asLong(-1))return error("PROVIDER_CONFIGURATION_CHANGED");
        if(!Set.of("web_search","web_fetch","web_download").contains(tool))throw new IllegalArgumentException("Unknown Internet tool");
        String hash=hash(request.toString().getBytes(StandardCharsets.UTF_8));
        if(!store.begin(call,task,tenant,user,agent,id,tool,hash))return store.replay(call);
        long start=System.nanoTime();Map<String,Object> result;
        try{
            store.reserve(p,hash((tenant+"\0"+user+"\0"+task).getBytes(StandardCharsets.UTF_8)));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(p.timeoutMillis());
            result=tool.equals("web_search")?search(p,request.path("arguments"),deadline):tool.equals("web_download")?download(request.path("arguments"),deadline):fetch(request.path("arguments"),deadline);
            var current=store.find(id);if(current==null||!current.enabled()||current.revision()!=p.revision())result=error("PROVIDER_CONFIGURATION_CHANGED");
        }catch(PublicWebHttp.Rejected e){result=error(e.code());}
        catch(IllegalArgumentException e){result=error("INVALID_TOOL_ARGUMENTS");}
        catch(Exception e){result=error("INTERNET_TOOL_UNAVAILABLE");}
        result=new LinkedHashMap<>(result);result.put("provider_id",id);result.put("request_id",call);
        result.put("retrieved_at",Instant.now().toString());result.put("authority","UNTRUSTED_SOURCE_DATA");
        store.finish(call,p,result,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));return result;
    }
    private Map<String,Object> search(InternetProviderStore.Provider p,JsonNode args,long deadline)throws Exception{
        fields(args,Set.of("query","search_type","count","language","time_range"));String query=text(args,"query",500);
        int count=integer(args,"count",5,1,10);String kind=args.path("search_type").asText("web");
        if(!Set.of("web","news").contains(kind))throw new PublicWebHttp.Rejected("SEARCH_TYPE_INVALID");
        String range=args.path("time_range").asText("");if(!Set.of("","day","week","month","year").contains(range))throw new PublicWebHttp.Rejected("TIME_RANGE_INVALID");
        String language=args.path("language").asText("");if(language.length()>30)throw new PublicWebHttp.Rejected("LANGUAGE_INVALID");
        var secrets=store.credentials(p);String key=secrets.getOrDefault("apiKey","");if(key.isBlank())throw new PublicWebHttp.Rejected("PROVIDER_CREDENTIAL_UNAVAILABLE");
        URI endpoint=PublicWebHttp.validate(p.endpoint());Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept","application/json");
        byte[] body=null;String method="GET";
        if(p.kind().equals("BRAVE")){
            String path=endpoint.toString();if(kind.equals("news")){if(!path.endsWith("/web/search"))throw new PublicWebHttp.Rejected("PROVIDER_NEWS_UNSUPPORTED");path=path.replace("/web/search","/news/search");}
            endpoint=URI.create(path+"?q="+encode(query)+"&count="+count+(language.isBlank()?"":"&search_lang="+encode(language))+(range.isBlank()?"":"&freshness="+Map.of("day","pd","week","pw","month","pm","year","py").get(range)));
            headers.put("X-Subscription-Token",key);
        }else if(p.kind().equals("TAVILY")){
            var payload=new LinkedHashMap<String,Object>();payload.put("query",query);payload.put("topic",kind.equals("news")?"news":"general");payload.put("max_results",count);
            payload.put("include_answer",false);payload.put("include_raw_content",false);payload.put("search_depth","basic");payload.put("include_usage",true);
            if(!range.isBlank())payload.put("time_range",range);if(!language.isBlank())payload.put("language",language);
            body=json.writeValueAsBytes(payload);method="POST";headers.put("Authorization","Bearer "+key);headers.put("Content-Type","application/json");
        }else{
            if(kind.equals("news"))throw new PublicWebHttp.Rejected("PROVIDER_NEWS_UNSUPPORTED");
            if(!language.isBlank())throw new PublicWebHttp.Rejected("PROVIDER_LANGUAGE_UNSUPPORTED");
            if(!Set.of("","/").contains(endpoint.getPath()))throw new PublicWebHttp.Rejected("PROVIDER_ENDPOINT_INVALID");
            var payload=json.createObjectNode().put("Query",query).put("Mode",0);Instant now=Instant.now();
            if(!range.isBlank()){long days=Map.of("day",1L,"week",7L,"month",31L,"year",366L).get(range);payload.put("FromTime",now.minusSeconds(days*86400).getEpochSecond());payload.put("ToTime",now.getEpochSecond());}
            body=json.writeValueAsBytes(payload);method="POST";headers.put("Content-Type","application/json; charset=utf-8");
            headers.put("X-TC-Action","SearchPro");headers.put("X-TC-Version","2025-05-08");headers.put("X-TC-Timestamp",String.valueOf(now.getEpochSecond()));
            var config=new NewsRuntimeProperties.WebSearch();config.setSecretId(secrets.getOrDefault("secretId",""));config.setSecretKey(key);
            if(config.getSecretId().isBlank())throw new PublicWebHttp.Rejected("PROVIDER_CREDENTIAL_UNAVAILABLE");
            headers.put("Authorization",signer.authorization(endpoint.getHost(),new String(body,StandardCharsets.UTF_8),now,config));
        }
        var response=http.request(endpoint,method,headers,body,deadline);
        if(response.status()==401||response.status()==403)throw new PublicWebHttp.Rejected("PROVIDER_AUTH_FAILED");
        if(response.status()==429)throw new PublicWebHttp.Rejected("PROVIDER_RATE_LIMITED");
        if(response.status()<200||response.status()>299)throw new PublicWebHttp.Rejected("PROVIDER_HTTP_ERROR"); // never forward credentials over redirects
        var root=json.readTree(response.body());JsonNode results;
        if(p.kind().equals("BRAVE"))results=kind.equals("news")?root.path("results"):root.path("web").path("results");
        else if(p.kind().equals("TAVILY"))results=root.path("results");
        else{root=root.path("Response");if(root.has("Error"))throw new PublicWebHttp.Rejected("PROVIDER_REJECTED_REQUEST");results=root.path("Pages");}
        if(!results.isArray())throw new PublicWebHttp.Rejected("PROVIDER_RESPONSE_INVALID");
        var items=new ArrayList<Map<String,Object>>();
        for(var raw:results){
            if(items.size()>=count)break;JsonNode row=raw.isTextual()?json.readTree(raw.asText()):raw;
            String url=first(row,"url","Url");try{PublicWebHttp.validate(url);}catch(RuntimeException bad){continue;}
            var item=new LinkedHashMap<String,Object>();item.put("title",redact(first(row,"title","Title"),secrets));item.put("url",redact(url,secrets));
            String snippet=redact(Jsoup.parse(first(row,"description","content","passage","snippet","summary")).text(),secrets);item.put("snippet",snippet.substring(0,Math.min(1600,snippet.length())));
            item.put("source",redact(first(row,"site_name","site","source"),secrets));String published=redact(first(row,"published_date","page_age","date"),secrets);item.put("published_at",published.isBlank()?null:published);item.put("retrieved_at",Instant.now().toString());items.add(item);
        }
        var result=new LinkedHashMap<String,Object>();result.put("status","OK");result.put("query",query);result.put("items",items);result.put("count",items.size());result.put("source_view","SEARCH_SNIPPETS_NOT_FULL_PAGES");
        if(root.path("usage").path("credits").isNumber())result.put("providerUsageCredits",root.path("usage").path("credits").numberValue());
        return result;
    }
    private Map<String,Object> download(JsonNode args,long deadline)throws Exception{
        fields(args,Set.of("url"));URI uri=PublicWebHttp.validate(text(args,"url",4096));PublicWebHttp.Response response=null;
        for(int i=0;i<=3;i++){
            robots.require(uri,deadline);response=http.request(uri,"GET",Map.of(),null,deadline);
            if(response.status()<300||response.status()>399)break;
            if(i==3||response.location().isBlank())throw new PublicWebHttp.Rejected("REDIRECT_LIMIT");
            URI next=PublicWebHttp.validate(uri.resolve(response.location()).toString());
            if(uri.getScheme().equals("https")&&!next.getScheme().equals("https"))throw new PublicWebHttp.Rejected("REDIRECT_DOWNGRADE");uri=next;
        }
        if(response.status()<200||response.status()>299)throw new PublicWebHttp.Rejected("DOWNLOAD_HTTP_ERROR");
        String type=response.contentType().split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(!Set.of("application/pdf","application/json","application/ld+json","text/csv","application/csv","text/plain","text/html","application/xhtml+xml").contains(type))throw new PublicWebHttp.Rejected("DOWNLOAD_CONTENT_TYPE_UNSUPPORTED");
        var result=new LinkedHashMap<String,Object>();result.put("status","OK");result.put("url",uri.toString());result.put("content_type",response.contentType());result.put("size",response.body().length);result.put("sha256",hash(response.body()));result.put("encoding","base64");result.put("content",Base64.getEncoder().encodeToString(response.body()));result.put("source_view","PUBLIC_FILE_BYTES");return result;
    }
    private Map<String,Object> fetch(JsonNode args,long deadline)throws Exception{
        fields(args,Set.of("url","max_content_length"));URI uri=PublicWebHttp.validate(text(args,"url",4096));
        int limit=integer(args,"max_content_length",12000,200,20000);PublicWebHttp.Response response=null;
        for(int i=0;i<=3;i++){
            robots.require(uri,deadline);response=http.request(uri,"GET",Map.of(),null,deadline);
            if(response.status()<300||response.status()>399)break;
            if(i==3||response.location().isBlank())throw new PublicWebHttp.Rejected("REDIRECT_LIMIT");
            URI next=PublicWebHttp.validate(uri.resolve(response.location()).toString());
            if(uri.getScheme().equals("https")&&!next.getScheme().equals("https"))throw new PublicWebHttp.Rejected("REDIRECT_DOWNGRADE");uri=next;
        }
        if(response.status()==401||response.status()==402||response.status()==403)throw new PublicWebHttp.Rejected("PAGE_ACCESS_DENIED");
        if(response.status()<200||response.status()>299)throw new PublicWebHttp.Rejected("PAGE_HTTP_ERROR");
        String type=response.contentType().toLowerCase(Locale.ROOT),title="",content;
        if(type.contains("html")){
            String charset=null;try{var cs=org.apache.http.entity.ContentType.parse(response.contentType()).getCharset();if(cs!=null)charset=cs.name();}catch(RuntimeException ignored){}
            var doc=Jsoup.parse(new java.io.ByteArrayInputStream(response.body()),charset,uri.toString());title=doc.title();
            doc.select("script,style,nav,footer,aside,form,iframe,noscript,[hidden],[aria-hidden=true]").remove();var main=doc.selectFirst("article,main");content=(main==null?doc.body():main).text();
        }else if(type.startsWith("text/")||type.contains("json")){
            java.nio.charset.Charset charset=StandardCharsets.UTF_8;try{var cs=org.apache.http.entity.ContentType.parse(response.contentType()).getCharset();if(cs!=null)charset=cs;}catch(RuntimeException ignored){}content=new String(response.body(),charset);
        }else throw new PublicWebHttp.Rejected("PAGE_CONTENT_TYPE_UNSUPPORTED");
        var result=new LinkedHashMap<String,Object>();result.put("status","OK");result.put("title",title);result.put("url",uri.toString());
        result.put("content",content.substring(0,Math.min(limit,content.length())));result.put("content_type",response.contentType());result.put("content_truncated",content.length()>limit);
        result.put("downloaded_bytes",response.body().length);result.put("source_sha256",hash(response.body()));result.put("source_view","PUBLIC_PAGE_EXTRACTED_TEXT");result.put("count",1);
        return result;
    }
    private static String redact(String value,Map<String,String> credentials){String clean=value;for(String secret:credentials.values())if(secret!=null&&!secret.isBlank())clean=clean.replace(secret,"[REDACTED]");return clean;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static Map<String,Object> error(String code){
        String guidance=switch(code){
            case "INVALID_TOOL_ARGUMENTS"->"Use the advertised tool schema: web_search takes query and optional search filters; web_fetch takes a single url and optional max_content_length; web_download takes a single url. Batched requests and prompt arguments are not supported.";
            case "PROVIDER_LANGUAGE_UNSUPPORTED"->"This engine does not support a language filter. Omit language or select another already authorized engine; query text may still be in the requested language. No search was performed.";
            case "PROVIDER_NEWS_UNSUPPORTED"->"This engine supports web search only. Use search_type=web or another already authorized engine.";
            case "PROVIDER_AUTH_FAILED","PROVIDER_CREDENTIAL_UNAVAILABLE"->"Provider credentials are unavailable or rejected. Do not guess credentials; use delivered local evidence and disclose the external-search gap.";
            case "PROVIDER_DISABLED","PROVIDER_CONFIGURATION_CHANGED"->"Provider configuration changed or is unavailable. A new authorization snapshot is required; do not bypass the model or Agent policy.";
            default->"Search did not establish data absence. Inspect errorCode, preserve the evidence gap and use only currently authorized alternatives.";
        };
        return Map.of("status","ERROR","errorCode",code,"count",0,"guidance",guidance);
    }
    private static String first(JsonNode n,String... keys){for(String k:keys)if(n.path(k).isTextual())return n.path(k).asText();return "";}
    private static String text(JsonNode n,String k,int max){if(!n.path(k).isTextual()||n.path(k).asText().isBlank()||n.path(k).asText().length()>max)throw new IllegalArgumentException("Invalid "+k);return n.path(k).asText();}
    private static int integer(JsonNode n,String k,int fallback,int min,int max){if(!n.has(k))return fallback;if(!n.path(k).isIntegralNumber()||n.path(k).asInt()<min||n.path(k).asInt()>max)throw new IllegalArgumentException("Invalid "+k);return n.path(k).asInt();}
    private static void fields(JsonNode n,Set<String> allowed){n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown tool argument");});}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
