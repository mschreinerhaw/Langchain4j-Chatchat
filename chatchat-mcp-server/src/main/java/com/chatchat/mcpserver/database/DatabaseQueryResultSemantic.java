package com.chatchat.mcpserver.database;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DatabaseQueryResultSemantic {
    private String resultSetName;
    private String businessEntity;
    private List<String> primaryKeys = new ArrayList<>();
    private String timeField;
    private String dataGranularity;
    private Map<String, String> unitDescriptions = new LinkedHashMap<>();
    private String emptyMeaning;
    private String modelUsage;

    public String getResultSetName() { return resultSetName; }
    public void setResultSetName(String resultSetName) { this.resultSetName = resultSetName; }
    public String getBusinessEntity() { return businessEntity; }
    public void setBusinessEntity(String businessEntity) { this.businessEntity = businessEntity; }
    public List<String> getPrimaryKeys() { return primaryKeys; }
    public void setPrimaryKeys(List<String> primaryKeys) { this.primaryKeys = primaryKeys == null ? new ArrayList<>() : new ArrayList<>(primaryKeys); }
    public String getTimeField() { return timeField; }
    public void setTimeField(String timeField) { this.timeField = timeField; }
    public String getDataGranularity() { return dataGranularity; }
    public void setDataGranularity(String dataGranularity) { this.dataGranularity = dataGranularity; }
    public Map<String, String> getUnitDescriptions() { return unitDescriptions; }
    public void setUnitDescriptions(Map<String, String> unitDescriptions) { this.unitDescriptions = unitDescriptions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(unitDescriptions); }
    public String getEmptyMeaning() { return emptyMeaning; }
    public void setEmptyMeaning(String emptyMeaning) { this.emptyMeaning = emptyMeaning; }
    public String getModelUsage() { return modelUsage; }
    public void setModelUsage(String modelUsage) { this.modelUsage = modelUsage; }
}
