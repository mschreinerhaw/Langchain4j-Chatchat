package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.runtime.coding.data.CodingInputRows;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Opt-in count/sum contract. Independently parses immutable original CSV bytes; never model code. */
@Component
public final class CodingCsvAggregateVerifier implements CodingResultVerifier {
    public static final String PROFILE="csv-count-sum-v1";
    private final McpPythonControlPlanePort python;
    public CodingCsvAggregateVerifier(McpPythonControlPlanePort python){this.python=python;}
    public String id(){return "runtime-csv-count-sum";}
    public String version(){return "v1";}
    public void validateRules(Map<String,Object> rules){
        if(!rules.keySet().equals(Set.of("rowSchema","valueField"))
            ||!(rules.get("valueField") instanceof String field)||!field.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Explicit CSV count/sum field required");
        new CodingSchemaResultVerifier().validateRules(Map.of("rowSchema",rules.get("rowSchema")));
    }
    public CodingEvidenceCollector.Validation verify(KernelDataScope scope,CodingInputSnapshotService.Snapshot input,
            Map<String,Object> rules,List<Map<String,Object>> rows){return verifyInputs(scope,List.of(input),rules,rows);}
    public CodingEvidenceCollector.Validation verifyInputs(KernelDataScope scope,List<CodingInputSnapshotService.Snapshot> inputs,
            Map<String,Object> rules,List<Map<String,Object>> rows){
        validateRules(rules);
        var schema=new CodingSchemaResultVerifier().verify(scope,inputs.get(0),Map.of("rowSchema",rules.get("rowSchema")),rows);
        var json=new ObjectMapper();var chunks=new ArrayList<Map<String,Object>>();
        for(var input:inputs)CodingInputRows.scan(input,f->python.downloadDataFile(scope.tenantId(),scope.userId(),f.fileId()),json,row->{if(row.get("record") instanceof Map<?,?> record)chunks.add((Map<String,Object>)record);else chunks.add(row);});
        // This profile deliberately supports one explicitly selected original CSV, not arbitrary documents or multi-file inference.
        var source=originalCsv(chunks);var expected=calculate(source,(String)rules.get("valueField"));
        var faults=new ArrayList<String>();if(!schema.passed())faults.add("ROW_SCHEMA");
        if(rows.size()!=1)faults.add("REPORT_ROW_COUNT");
        Map<?,?> actual=rows.size()==1&&rows.get(0).get("verifiedSummary") instanceof Map<?,?> m?m:Map.of();
        if(!Objects.equals(expected.get("sourceSha256"),actual.get("sourceSha256")))faults.add("SOURCE_HASH");
        if(!(actual.get("rowCount") instanceof Number count)||count.longValue()!=((Number)expected.get("rowCount")).longValue()||count.doubleValue()!=count.longValue())faults.add("ROW_COUNT");
        try{if(!(actual.get("sum") instanceof String text)||new BigDecimal(text).compareTo(new BigDecimal((String)expected.get("sum")))!=0)faults.add("DECIMAL_SUM");}
        catch(NumberFormatException invalid){faults.add("DECIMAL_SUM");}
        if(rows.size()==1&&!"COMPLETE".equals(rows.get(0).get("analysisStatus")))faults.add("OBJECTIVE_INCOMPLETE");
        return new CodingEvidenceCollector.Validation(faults.isEmpty(),id(),version(),Map.of(
            "validationScope","EXPLICIT_CSV_COUNT_SUM","valueField",rules.get("valueField"),"expected",expected,
            "faults",faults,"independentInputRead",true,"scopeLimit","Only the contracted row count, exact decimal sum and source binding; no financial interpretation"),true);
    }
    static byte[] originalCsv(List<Map<String,Object>> chunks){
        if(chunks.isEmpty())throw new IllegalArgumentException("CSV original input is missing");
        var first=chunks.get(0);String asset=Objects.toString(first.get("assetId"),"");
        if(asset.isEmpty()||!Objects.toString(first.get("fileName"),"").toLowerCase(Locale.ROOT).endsWith(".csv")
                ||!(first.get("chunkCount") instanceof Number n)||n.intValue()!=chunks.size()||chunks.size()>16)
            throw new IllegalArgumentException("Select exactly one complete original CSV for count/sum verification");
        var ordered=new TreeMap<Integer,byte[]>();
        for(var row:chunks){
            if(!asset.equals(row.get("assetId"))||!Objects.equals(first.get("sha256"),row.get("sha256"))
                    ||!Objects.equals(first.get("chunkCount"),row.get("chunkCount"))||!(row.get("chunkIndex") instanceof Number number))
                throw new SecurityException("CSV chunk identity mismatch");
            byte[] bytes=Base64.getDecoder().decode((String)row.get("contentBase64"));
            if(!hash(bytes).equals(row.get("chunkSha256"))||ordered.put(number.intValue(),bytes)!=null)throw new SecurityException("CSV chunk integrity mismatch");
        }
        var result=new java.io.ByteArrayOutputStream();for(int i=0;i<chunks.size();i++){var bytes=ordered.get(i);if(bytes==null)throw new SecurityException("CSV chunk missing");result.writeBytes(bytes);}
        byte[] bytes=result.toByteArray();if(!(first.get("size") instanceof Number size)||size.longValue()!=bytes.length||!hash(bytes).equals(first.get("sha256")))throw new SecurityException("CSV original integrity mismatch");return bytes;
    }
    static Map<String,Object> calculate(byte[] bytes,String field){
        if(bytes.length>16*1024*1024)throw new IllegalArgumentException("CSV verification input too large");
        long count=0;BigDecimal sum=BigDecimal.ZERO;
        try(var records=new CsvMapper().readerFor(Map.class).with(CsvSchema.emptySchema().withHeader()).readValues(bytes)){
            records.hasNextValue(); // Parse the real header even for a valid zero-row CSV.
            var header=(CsvSchema)records.getParser().getSchema();
            if(header.size()==0||header.column(field)==null)
                throw new IllegalArgumentException("CSV header does not declare the selected numeric field");
            while(records.hasNext()){
                Map<?,?> row=(Map<?,?>)records.next();Object value=row.get(field);
                if(!(value instanceof String text)||!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")||text.length()>128)
                    throw new IllegalArgumentException("Count/sum requires a non-null exact decimal in every selected row");
                sum=sum.add(new BigDecimal(text));if(++count>1_000_000)throw new IllegalArgumentException("CSV verification row limit exceeded");
            }
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("CSV original cannot be parsed",invalid);}
        return Map.of("sourceSha256",hash(bytes),"rowCount",count,"sum",sum.toPlainString());
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
