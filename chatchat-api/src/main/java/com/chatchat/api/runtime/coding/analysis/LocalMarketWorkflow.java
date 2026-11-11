package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.api.runtime.coding.knowledge.CodingResourceReader;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.common.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** Domain data-supply workflow. Uses existing grants and collected market
 * readers; Cline owns security selection, analysis and report generation. */
@Service
public class LocalMarketWorkflow {
    private final CodingResourceReader reader;private final ResourceAuthorizationPort authorization;
    private final AgentResourceScopePort scopes;private final ObjectMapper json;
    public LocalMarketWorkflow(CodingResourceReader reader,ResourceAuthorizationPort authorization,AgentResourceScopePort scopes,ObjectMapper json){this.reader=reader;this.authorization=authorization;this.scopes=scopes;this.json=json;}
    public boolean requested(String question){return question!=null&&question.matches("(?is).*(股票|行情|股价|证券|stock|equity|market quotes).*" );}
    public Map<String,Dataset> supply(String tenant,String user,String agent,String question){
        if(!requested(question))return Map.of();
        boolean allowed=agent!=null&&!agent.isBlank()?scopes.resolve(tenant,user,agent,true).contains(AgentResourcePolicy.MARKET)
            :authorization.allowedIds(AgentResourcePolicy.GRANT_TYPE,tenant,user,Set.of(),Set.of(AgentResourcePolicy.MARKET)).contains(AgentResourcePolicy.MARKET);
        if(!allowed)throw new SecurityException("Local market workflow requires an existing financial_market grant");
        var result=new LinkedHashMap<String,Dataset>();
        var candidates=read("market_resolve_security",Map.of("query",question.substring(0,Math.min(200,question.length())),"limit",20));
        result.put("market:security-directory",new Dataset("market:security-directory",Map.of("recordMeaning","SECURITY_DIRECTORY_CANDIDATES_NOT_QUOTES"),List.of(candidates)));
        var seen=new HashSet<String>();
        for(int offset=0;offset<=40;offset+=20){
            var catalog=read("market_search",Map.of("query","","offset",offset,"limit",20));
            for(var raw:items(catalog)){
                String dataset=Objects.toString(raw.get("dataset_code"),"");if(dataset.isBlank())continue;
                var coverage=read("market_get_snapshot",Map.of("dataset",dataset,"limit",50));
                for(var row:items(coverage)){
                    Object source=row.get("source_id");String day=Objects.toString(row.get("observation_date"),"");if(!(source instanceof Number)||day.length()<10)continue;
                    String key=dataset+":"+source+":"+day.substring(0,10);if(!seen.add(key))continue;
                    var snapshot=read("market_get_day_snapshot",Map.of("dataset",dataset,"source_id",source,"trade_date",day.substring(0,10)));
                    if(!Boolean.TRUE.equals(snapshot.get("complete")))throw new IllegalStateException("Local market snapshot is incomplete: "+key);
                    String ref="market:snapshot:"+key;var context=new LinkedHashMap<String,Object>();context.put("sourceDescription",snapshot.getOrDefault("asset",Map.of()));context.put("snapshotSemantics",Map.of("realtime",false,"tradeDate",day.substring(0,10),"sourceId",source,"securityResolved",!items(candidates).isEmpty(),"selection","Full collected source/day; Cline must verify the requested security and disclose absent matches"));context.put("recordMeaning","COLLECTED_MARKET_OBSERVATIONS");
                    result.put(ref,new Dataset(ref,context,items(snapshot)));
                }
            }
            if(catalog.get("next_offset")==null)break;
        }
        if(seen.isEmpty())result.put("market:missing-data",new Dataset("market:missing-data",Map.of("recordMeaning","DATA_SUPPLY_STATUS_NOT_MEASUREMENTS"),List.of(Map.of("status","NO_COLLECTED_MARKET_SNAPSHOT","securityDirectory",candidates))));
        return result;
    }
    private Map<String,Object> read(String tool,Map<String,Object> args){return reader.read(tool,json.valueToTree(args));}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> value){return value.get("items") instanceof List<?> rows?(List<Map<String,Object>>)(List<?>)rows:List.of();}
}
