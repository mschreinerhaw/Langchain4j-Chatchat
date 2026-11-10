package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.runtime.coding.data.CodingInputRows;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Explicit customer acceptance contract. Recompute source fields, never generated Python. */
@Component
public final class CodingCustomerAccountVerifier implements CodingResultVerifier {
    public static final String PROFILE="customer-account-v1";
    private static final List<String> MONEY=List.of("assetTotal","cash","securitiesValue","accountDayPnl",
        "positionMarketValue","positionCost","positionDayPnl","positionCumulativeRealizedPnl","transactionQuantity","transactionGrossAmount");
    private static final List<String> COUNTS=List.of("assetRows","positionRows","transactionRows");
    private final McpPythonControlPlanePort python;
    public CodingCustomerAccountVerifier(McpPythonControlPlanePort python){this.python=python;}
    public String id(){return "runtime-customer-account";}
    public String version(){return "v1";}

    public static Map<String,Object> summarySchema(){
        var p=new LinkedHashMap<String,Object>();
        for(String field:List.of("customerNumber","businessDate","inputSha256","transactionMetricBasis"))p.put(field,Map.of("type","string"));
        MONEY.forEach(field->p.put(field,Map.of("type","string")));
        COUNTS.forEach(field->p.put(field,Map.of("type","integer")));
        return Map.of("type","object","properties",p,"required",List.copyOf(p.keySet()),"additionalProperties",false);
    }
    public void validateRules(Map<String,Object> rules){
        if(!rules.keySet().equals(Set.of("rowSchema","customerNumber","sourceVersions"))
            ||!(rules.get("customerNumber") instanceof String customer)||!customer.matches("[0-9]{1,32}")
            ||!(rules.get("sourceVersions") instanceof List<?> sources)||sources.isEmpty()||sources.size()>8)
            throw new IllegalArgumentException("Explicit customer and delivered source versions required");
        new CodingSchemaResultVerifier().validateRules(Map.of("rowSchema",rules.get("rowSchema")));
    }
    public CodingEvidenceCollector.Validation verify(KernelDataScope scope,CodingInputSnapshotService.Snapshot input,
            Map<String,Object> rules,List<Map<String,Object>> rows){return verifyInputs(scope,List.of(input),rules,rows);}
    public CodingEvidenceCollector.Validation verifyInputs(KernelDataScope scope,List<CodingInputSnapshotService.Snapshot> inputs,
            Map<String,Object> rules,List<Map<String,Object>> rows){
        validateRules(rules);
        var faults=new LinkedHashSet<String>();var raw=new ArrayList<Map<String,Object>>();
        var json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        for(var input:inputs)CodingInputRows.scan(input,f->python.downloadDataFile(scope.tenantId(),scope.userId(),f.fileId()),json,raw::add);
        if(raw.size()!=inputs.stream().mapToLong(CodingInputSnapshotService.Snapshot::rowCount).sum())faults.add("SNAPSHOT_ROW_COUNT");
        Map<String,Object> expected=Map.of();
        try{expected=calculate(raw,(String)rules.get("customerNumber"),(List<?>)rules.get("sourceVersions"),inputs.get(0).sha256());}
        catch(IllegalArgumentException|IllegalStateException invalid){faults.add("INPUT_SCOPE_OR_METRIC_UNAVAILABLE");}
        var structural=new CodingSchemaResultVerifier().verify(scope,inputs.get(0),Map.of("rowSchema",rules.get("rowSchema")),rows);
        if(!structural.passed())faults.add("REPORT_SCHEMA");
        if(rows.size()!=1)faults.add("REPORT_ROW_COUNT");
        Map<?,?> actual=rows.size()==1&&rows.get(0).get("verifiedAccountSummary") instanceof Map<?,?> m?m:Map.of();
        for(var field:expected.entrySet()){
            Object value=actual.get(field.getKey());boolean equal;
            try{
                if(MONEY.contains(field.getKey()))equal=value instanceof String s&&decimal(s).compareTo(decimal(field.getValue()))==0;
                else if(COUNTS.contains(field.getKey()))equal=value instanceof Number n&&n.doubleValue()==n.longValue()&&n.longValue()==((Number)field.getValue()).longValue();
                else equal=Objects.equals(value,field.getValue());
            }catch(IllegalArgumentException invalid){equal=false;}
            if(!equal)faults.add("METRIC_"+field.getKey());
        }
        if(rows.size()==1){
            var row=rows.get(0);
            if(!"COMPLETE".equals(row.get("analysisStatus")))faults.add("OBJECTIVE_INCOMPLETE");
            String report=Objects.toString(row.get("reportMarkdown"),"");
            if(!report.contains((String)rules.get("customerNumber"))||report.isBlank())faults.add("REPORT_CUSTOMER");
            if(!(row.get("limitations") instanceof List<?> limitations)||limitations.isEmpty())faults.add("SCOPE_LIMITATIONS_REQUIRED");
            var used=new LinkedHashSet<String>();for(var item:(List<?>)rules.get("sourceVersions")){
                var version=map(item);if(((Number)version.get("rowCount")).longValue()>0)used.add((String)version.get("datasetReference"));
            }
            if(!(row.get("evidenceReferences") instanceof List<?> refs)||!new HashSet<>(refs).containsAll(used))faults.add("SOURCE_REFERENCES");
        }
        return new CodingEvidenceCollector.Validation(faults.isEmpty(),id(),version(),Map.of(
            "validationScope","EXPLICIT_CUSTOMER_ACCOUNT_SOURCE_METRICS","independentInputRead",true,
            "expected",expected,"faults",List.copyOf(faults),"sourceRows",raw.size(),
            "scopeLimit","Recomputed declared source fields and transaction quantity/amount only; narrative, long-term preferences, fees, return formulas and cross-scope reconciliation require separate review."),true);
    }
    static Map<String,Object> calculate(List<Map<String,Object>> rows,String customer,List<?> versions,String inputHash){
        var groups=new LinkedHashMap<String,List<Map<String,Object>>>();var declared=new LinkedHashMap<String,Map<String,Object>>();
        for(var item:versions){var v=map(item);String ref=(String)v.get("datasetReference");
            if(ref==null||declared.put(ref,v)!=null)throw new IllegalArgumentException("Duplicate source declaration");groups.put(ref,new ArrayList<>());}
        for(var row:rows){
            String ref=(String)row.get("datasetReference");var record=map(row.get("record"));
            if(!groups.containsKey(ref)||!customer.equals(record.get("KHH")))throw new IllegalArgumentException("Customer/source mismatch");
            groups.get(ref).add(record);
        }
        for(var e:groups.entrySet())if(e.getValue().size()!=((Number)declared.get(e.getKey()).get("rowCount")).longValue())throw new IllegalArgumentException("Source row count mismatch");
        String asset=role(declared,Set.of("ZZC","ZJYE","ZQSZ","DRYK","RQ"));
        String positions=role(declared,Set.of("ZXSZ","CCCB","DRYK","LJYK","ZQSL"));
        List<String> settlements=candidates(declared,Set.of("CJRQ","CJJE","CJSL","KHH"));
        if(settlements.size()>1)throw new IllegalArgumentException("Ambiguous transaction source");
        boolean settled=!settlements.isEmpty();
        String trades=settled?settlements.get(0):role(declared,Set.of("WTRQ","CJSL","CJJG","WTH"));
        if(settled&&!map(declared.get(trades).get("fieldDefinitions")).keySet().containsAll(Set.of("CJRQ","CJJE","CJSL")))throw new IllegalArgumentException("Missing settlement field declarations");
        var assets=groups.get(asset);if(assets.size()!=1)throw new IllegalArgumentException("Single asset observation required");
        String date=Objects.toString(assets.get(0).get("RQ"),"");
        if(!date.matches("[0-9]{8}"))throw new IllegalArgumentException("Explicit asset date required");
        java.time.LocalDate.parse(date,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        for(String ref:List.of(asset,positions,trades)){
            var binding=map(declared.get(ref).get("templateParameterBinding"));var params=map(binding.get("effectiveParameters"));
            if(!customer.equals(params.get("khh"))||!date.equals(params.get("ksrq"))||!date.equals(params.get("jsrq")))throw new IllegalArgumentException("Template customer/date mismatch");
        }
        var result=new LinkedHashMap<String,Object>();result.put("customerNumber",customer);result.put("businessDate",date);result.put("inputSha256",inputHash);
        var a=assets.get(0);result.put("assetTotal",decimal(a.get("ZZC")).toPlainString());result.put("cash",decimal(a.get("ZJYE")).toPlainString());
        result.put("securitiesValue",decimal(a.get("ZQSZ")).toPlainString());result.put("accountDayPnl",decimal(a.get("DRYK")).toPlainString());
        var holds=groups.get(positions);
        for(var entry:Map.of("positionMarketValue","ZXSZ","positionCost","CCCB","positionDayPnl","DRYK","positionCumulativeRealizedPnl","LJYK").entrySet())
            result.put(entry.getKey(),sum(holds,entry.getValue()).toPlainString());
        var transactions=groups.get(trades);BigDecimal amount=BigDecimal.ZERO;
        for(var t:transactions){
            if(!date.equals(Objects.toString(t.get(settled?"CJRQ":"WTRQ"))))throw new IllegalArgumentException("Transaction date mismatch");
            amount=amount.add(settled?decimal(t.get("CJJE")):decimal(t.get("CJSL")).multiply(decimal(t.get("CJJG"))));
        }
        result.put("transactionQuantity",sum(transactions,"CJSL").toPlainString());result.put("transactionGrossAmount",amount.toPlainString());
        result.put("transactionMetricBasis",settled?"SETTLEMENT_CJJE":"ORDER_EXECUTED_QUANTITY_TIMES_PRICE_EXCLUDING_FEES");
        result.put("assetRows",assets.size());result.put("positionRows",holds.size());result.put("transactionRows",transactions.size());return result;
    }
    private static List<String> candidates(Map<String,Map<String,Object>> versions,Set<String> fields){
        return versions.entrySet().stream().filter(e->{var schema=map(e.getValue().get("fieldSchema"));return schema.keySet().containsAll(fields);}).map(Map.Entry::getKey).toList();
    }
    private static String role(Map<String,Map<String,Object>> versions,Set<String> fields){
        var found=candidates(versions,fields);if(found.size()!=1)throw new IllegalArgumentException("Absent/ambiguous account source");
        var definitions=map(versions.get(found.get(0)).get("fieldDefinitions"));if(!definitions.keySet().containsAll(fields))throw new IllegalArgumentException("Missing field declarations");return found.get(0);
    }
    private static BigDecimal sum(List<Map<String,Object>> rows,String field){return rows.stream().map(r->decimal(r.get(field))).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static BigDecimal decimal(Object value){
        if(!(value instanceof String||value instanceof Number)||!value.toString().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")||value.toString().length()>128)throw new IllegalArgumentException("Explicit exact numeric field required");
        return new BigDecimal(value.toString());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("Object required");return (Map<String,Object>)m;}
}
