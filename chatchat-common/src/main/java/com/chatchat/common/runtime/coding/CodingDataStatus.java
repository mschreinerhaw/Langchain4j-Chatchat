package com.chatchat.common.runtime.coding;

import java.time.LocalDate;
import java.util.*;

/** Observed data facts, never a task terminal decision or permission to acquire more data. */
public enum CodingDataStatus {
    AVAILABLE, EMPTY_RESULT, SOURCE_EMPTY, DATE_MISMATCH, FILTER_MISMATCH,
    FIELD_MISSING, PARTIAL_DATA, ACCESS_DENIED, SOURCE_UNAVAILABLE, UNKNOWN;

    public static final String VERSION="runtime_os.data_observation.v1";

    public static Map<String,Object> describe(List<? extends Map<String,?>> rows, boolean complete,
            Collection<String> requiredFields, Map<String,?> query) {
        var missing=new TreeSet<String>();int incompleteRows=0;boolean dateMismatch=false,filterMismatch=false;
        for(var row:rows) {
            boolean absent=false;
            for(String field:requiredFields)if(!row.containsKey(field)||row.get(field)==null
                    ||row.get(field) instanceof String s&&s.isBlank()) {missing.add(field);absent=true;}
            if(absent)incompleteRows++;
            Object date=row.getOrDefault("observation_date",null);
            if(date==null)date=row.get("trade_date");
            if(date!=null)try {
                var actual=LocalDate.parse(date.toString().substring(0,10));
                Object start=query.get("start_date"),end=query.get("end_date"),day=query.get("trade_date");
                if(start!=null&&actual.isBefore(LocalDate.parse(start.toString()))
                        ||end!=null&&actual.isAfter(LocalDate.parse(end.toString()))
                        ||day!=null&&!actual.equals(LocalDate.parse(day.toString())))dateMismatch=true;
            }catch(RuntimeException unknownDate) { /* Unparseable dates do not prove a date mismatch. */ }
            if(query.get("filters") instanceof Map<?,?> filters)for(var filter:filters.entrySet())
                if(row.containsKey(filter.getKey())&&filter.getValue()!=null&&row.get(filter.getKey())!=null
                        &&!Objects.equals(row.get(filter.getKey()).toString(),filter.getValue().toString()))filterMismatch=true;
        }
        var states=new ArrayList<String>();
        if(dateMismatch)states.add(DATE_MISMATCH.name());
        if(filterMismatch)states.add(FILTER_MISMATCH.name());
        if(!missing.isEmpty())states.add(FIELD_MISSING.name());
        if(!complete)states.add(PARTIAL_DATA.name());
        if(rows.isEmpty()&&complete)states.add(EMPTY_RESULT.name());
        if(states.isEmpty())states.add(AVAILABLE.name());
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",VERSION);result.put("status",states.get(0));result.put("conditions",List.copyOf(states));
        result.put("returnedRowCount",rows.size());result.put("complete",complete);
        result.put("missingFields",List.copyOf(missing));result.put("rowsMissingRequiredFields",incompleteRows);
        result.put("cause",rows.isEmpty()?"UNKNOWN":"OBSERVED_RESPONSE_FACTS");
        result.put("sourceEmptyProven",false); // Even a complete day/slice is not a complete source census.
        result.put("automaticRetryAuthorized",false);
        return result;
    }
}
