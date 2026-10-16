package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Independent ledger PoC. Calendar, precision and formula are trusted plan inputs, never inferred. */
@Component
public final class CodingFinancialResultVerifier implements CodingResultVerifier {
    private com.chatchat.common.mcp.python.McpPythonControlPlanePort python;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPython(com.chatchat.common.mcp.python.McpPythonControlPlanePort value){this.python=value;}
    public String id(){return "runtime-financial-ledger";}
    public String version(){return "v1";}
    @Override public CodingEvidenceCollector.Validation verifyInputs(KernelDataScope scope,List<CodingInputSnapshotService.Snapshot> inputs,
        Map<String,Object> rules,List<Map<String,Object>> rows) {
        if(python==null)throw new SecurityException("Financial validation requires independently readable input bytes");
        var basic=verify(scope,inputs.get(0),rules,rows);
        Set<String> faults=new LinkedHashSet<>((List<String>)basic.checks().get("faults"));
        Map<String,Long> expected=new TreeMap<>(),actual=new TreeMap<>();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var input:inputs) {
            for(var file:input.chunked()?input.chunks():CodingInputSnapshotService.mountedFiles(input)) {
            byte[] bytes=python.downloadDataFile(scope.tenantId(),scope.userId(),file.fileId());
            if(!McpPythonExecutionProofVerifier.sha256(bytes).equals(file.sha256()))throw new SecurityException("Financial input content changed");
            for(String line:new String(bytes,java.nio.charset.StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank()) {
                try {
                    Map<String,Object> raw=json.readValue(line,new com.fasterxml.jackson.core.type.TypeReference<>(){});
                    for(String field:List.of("assets","netFlow","returnRate"))if(!(raw.get(field) instanceof String))faults.add("FIELD_TYPE");
                    expected.merge(sourceKey(raw),1L,Long::sum);
                }catch(java.io.IOException invalid){throw new IllegalStateException("Financial input JSON invalid",invalid);}
            }
            }
        }
        rows.forEach(row->actual.merge(sourceKey(row),1L,Long::sum));
        if(!actual.equals(expected))faults.add("SOURCE_VALUE_CHANGED");
        var checks=new LinkedHashMap<>(basic.checks());checks.put("faults",List.copyOf(faults));
        checks.put("sourceReconciled",actual.equals(expected));checks.put("inputVersions",inputs.stream().map(CodingInputSnapshotService.Snapshot::reference).toList());
        return new CodingEvidenceCollector.Validation(faults.isEmpty(),id(),version(),checks,true);
    }
    private static String sourceKey(Map<String,Object> row) {
        return List.of("date","assets","netFlow","returnRate").stream().map(field->Objects.toString(row.get(field),"<missing>")).collect(java.util.stream.Collectors.joining("\0"));
    }
    public void validateRules(Map<String,Object> rules) {
        if(!rules.keySet().equals(Set.of("tradingDates","openingAssets","moneyScale","returnScale","roundingMode")))throw new IllegalArgumentException("Exact explicit financial rules required");
        if(!(rules.get("tradingDates") instanceof List<?> dates)||dates.isEmpty()||dates.size()>10000)throw new IllegalArgumentException("Trading calendar required");
        LocalDate previous=null;for(Object date:dates){var next=LocalDate.parse((String)date);if(previous!=null&&!next.isAfter(previous))throw new IllegalArgumentException("Calendar must be unique and ordered");previous=next;}
        decimal(rules.get("openingAssets"));scale(rules,"moneyScale");scale(rules,"returnScale");
        RoundingMode.valueOf((String)rules.get("roundingMode"));
    }
    public CodingEvidenceCollector.Validation verify(KernelDataScope scope,CodingInputSnapshotService.Snapshot input,
        Map<String,Object> rules,List<Map<String,Object>> rows) {
        validateRules(rules);Set<String> faults=new LinkedHashSet<>();Set<String> seen=new HashSet<>();
        var dates=(List<?>)rules.get("tradingDates");int money=scale(rules,"moneyScale"),returns=scale(rules,"returnScale");
        var rounding=RoundingMode.valueOf((String)rules.get("roundingMode"));
        BigDecimal previous=decimal(rules.get("openingAssets"));String previousDate=null;
        for(var row:rows) {
            if(!(row.get("date") instanceof String date)||!dates.contains(date)){faults.add("TRADING_CALENDAR");continue;}
            if(!seen.add(date))faults.add("DUPLICATE_DATE");
            if(previousDate!=null&&date.compareTo(previousDate)<0)faults.add("DATE_ORDER");previousDate=date;
            try {
                var assets=decimal(row.get("assets"));var flow=decimal(row.get("netFlow"));var rate=decimal(row.get("returnRate"));
                if(assets.scale()>money||flow.scale()>money||rate.scale()>returns)faults.add("DECIMAL_PRECISION");
                if(previous.signum()==0)faults.add("ZERO_RETURN_DENOMINATOR");
                else {
                    var expected=assets.subtract(previous).subtract(flow).divide(previous,returns,rounding);
                    if(rate.compareTo(expected)!=0)faults.add("RETURN_RECONCILIATION");
                }
                previous=assets;
            }catch(IllegalArgumentException|ArithmeticException invalid){faults.add("FIELD_TYPE");}
        }
        if(seen.size()!=dates.size()||!seen.containsAll(dates))faults.add("MISSING_TRADING_DATE");
        return new CodingEvidenceCollector.Validation(faults.isEmpty(),id(),version(),Map.of(
            "faults",List.copyOf(faults),"formula","(assets - previousAssets - netFlow) / previousAssets",
            "rules",rules,"inputSnapshotRef",input.reference(),"rows",rows.size(),"businessDataModified",false),true);
    }
    private static BigDecimal decimal(Object value){if(!(value instanceof String text)||!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))throw new IllegalArgumentException("Exact decimal strings required");return new BigDecimal(text);}
    private static int scale(Map<String,Object> rules,String key){if(!(rules.get(key) instanceof Number number)||number.intValue()<0||number.intValue()>12||number.doubleValue()!=number.intValue())throw new IllegalArgumentException("Bounded decimal scale required");return number.intValue();}
}
