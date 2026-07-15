package com.chatchat.mcpserver.database;

public class DatabaseQueryParameterMapping {
    private String parameter;
    private String sourceType = "USER_INPUT";
    private String sourceKey;
    private String sourceNode;
    private String sourceExpression;
    private Object defaultValue;
    private Boolean required = false;

    public String getParameter() { return parameter; }
    public void setParameter(String parameter) { this.parameter = parameter; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getSourceNode() { return sourceNode; }
    public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }
    public String getSourceExpression() { return sourceExpression; }
    public void setSourceExpression(String sourceExpression) { this.sourceExpression = sourceExpression; }
    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }
}
