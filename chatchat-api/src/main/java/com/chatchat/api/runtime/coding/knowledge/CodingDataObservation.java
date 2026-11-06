package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.common.runtime.coding.CodingDataStatus;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import java.time.Instant;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;

/** Bounded observation metadata added after source hashing, not part of the financial source version. */
final class CodingDataObservation {
    private CodingDataObservation() { }
    static Map<String,Object> describe(CodingTaskContract contract,String tool,Map<String,Object> query,
            Map<String,Object> response,RuntimeException failure,Instant started,long elapsedMs) {
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",CodingDataStatus.VERSION);result.put("tool",tool);result.put("query",query);
        result.put("startedAt",started.toString());result.put("finishedAt",Instant.now().toString());result.put("elapsedMs",elapsedMs);
        result.put("resourceId",CodingResourceReader.resource(tool));
        result.put("recordKind",Set.of("market_search","market_resolve_security","knowledge_get_document","knowledge_list_sections").contains(tool)
            ?"METADATA":tool.startsWith("market_get_")?"OBSERVATIONS":"DOCUMENTS");
        result.put("availableTools",contract.authorization().allowedTools().stream()
            .filter(t->CodingResourceReader.TOOLS.contains(t)||t.startsWith("knowledge_")||t.equals("data_request")).toList());
        Object availability=contract.runtimeContext().get("internetAvailability");
        result.put("searchAvailability",availability==null?Map.of("status","UNKNOWN","serviceChecked",false):availability);
        if(failure!=null) {
            int status=failure instanceof ResponseStatusException http?http.getStatusCode().value():0;
            var state=status==401||status==403?CodingDataStatus.ACCESS_DENIED
                :status>=500?CodingDataStatus.SOURCE_UNAVAILABLE:CodingDataStatus.UNKNOWN;
            result.put("status",state.name());result.put("conditions",List.of(state.name()));
            result.put("returnedRowCount",null);result.put("httpStatus",status==0?null:status);
            result.put("errorType",failure.getClass().getSimpleName());
            // Full original failure remains with server diagnostics; avoid leaking internal service credentials.
            result.put("error",state==CodingDataStatus.ACCESS_DENIED?"Source access denied; no data disclosed"
                :state==CodingDataStatus.SOURCE_UNAVAILABLE?"Source request unavailable; no successful result observed"
                :"Read failed; inspect scoped execution evidence; no empty result inferred");
        } else {
            Object raw=response.get("items");
            if(raw==null)raw=response.get("chunks");
            if(raw==null)raw=response.get("hits");
            if(raw instanceof List<?> list&&list.stream().allMatch(x->x instanceof Map<?,?>)) {
                @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)list;
                result.putAll(CodingDataStatus.describe(rows,!Boolean.FALSE.equals(response.get("complete"))
                    &&!Boolean.TRUE.equals(response.get("window_may_be_truncated")),List.of(),query));
            } else {
                Object count=response.get("count");
                result.put("status",Boolean.FALSE.equals(response.get("complete"))?"PARTIAL_DATA"
                    :count instanceof Number n?(n.longValue()==0?"EMPTY_RESULT":"AVAILABLE"):"UNKNOWN");
                result.put("returnedRowCount",count instanceof Number?count:null);
                result.put("cause","UNKNOWN");result.put("sourceEmptyProven",false);
            }
            result.put("sourceStatus",response.get("status"));
            if("EMPTY_RESULT".equals(result.get("status"))&&response.get("source_coverage") instanceof Map<?,?> coverage
                    &&"RETAINED_VALID_DATASET".equals(coverage.get("scope"))&&Boolean.TRUE.equals(coverage.get("empty"))
                    &&Objects.equals(query.get("dataset"),coverage.get("dataset"))
                    &&List.of("daily","weekly").equals(coverage.get("readModes"))) {
                result.put("status","SOURCE_EMPTY");result.put("conditions",List.of("SOURCE_EMPTY","EMPTY_RESULT"));
                result.put("sourceEmptyProven",true);result.put("cause","BOUNDED_RETAINED_DATASET_EXISTENCE_CHECKS");
                result.put("coverageEvidence",coverage);
            }
            if("EMPTY_RESULT".equals(result.get("status"))&&response.get("asset") instanceof Map<?,?> asset
                    &&asset.get("last_observation_date")!=null) {
                Object requested=query.containsKey("trade_date")?query.get("trade_date"):query.get("start_date");
                try {
                    var latest=java.time.LocalDate.parse(asset.get("last_observation_date").toString().substring(0,10));
                    if(requested!=null&&java.time.LocalDate.parse(requested.toString()).isAfter(latest)) {
                        result.put("status","DATE_MISMATCH");result.put("conditions",List.of("DATE_MISMATCH","EMPTY_RESULT"));
                        result.put("cause","REQUEST_AFTER_OBSERVED_CATALOG_MAX_DATE");
                        result.put("coverageEvidence",Map.of("last_observation_date",latest.toString(),
                            "scope","Observed catalog maximum for this dataset; not trading-calendar or per-security completeness evidence"));
                    }
                }catch(RuntimeException unknownCoverage) { /* Keep UNKNOWN cause; do not guess from invalid dates. */ }
            }
            if("ERROR".equals(response.get("status"))) {
                String code=Objects.toString(response.get("errorCode"),"");
                result.put("status",Set.of("SEARCH_SERVICE_UNAVAILABLE","PROVIDER_UNAVAILABLE","SEARCH_PROVIDER_UNAVAILABLE").contains(code)?"SOURCE_UNAVAILABLE"
                    :Set.of("INTERNET_NOT_AUTHORIZED","ACCESS_DENIED").contains(code)?"ACCESS_DENIED":"UNKNOWN");
                result.put("conditions",List.of(result.get("status")));
                result.put("returnedRowCount",null);
                result.put("errorCode",response.get("errorCode"));result.put("error",response.get("message"));
            }
            for(String field:List.of("dataset","source_id","trade_date","data_as_of","evidence_id","calculationInput"))
                if(response.containsKey(field))result.put(field,response.get(field));
        }
        result.putIfAbsent("conditions",List.of(result.get("status")));
        result.putIfAbsent("sourceEmptyProven",false);
        result.put("automaticRetryAuthorized",false);
        result.put("recoveryPolicy","Choose only available authorized tools within the original task budget, date and file limits. Inspect template/parameters, dates, security/market/aliases and catalog coverage; consider other authorized sources, a bounded day file, then authorized Search. An empty slice does not prove source absence. Avoid repeating an unchanged query; retain attempted paths and unsupported analyses in a PARTIAL or unavailable report. Do not invent data or units.");
        return result;
    }
}
