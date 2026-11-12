package com.chatchat.api.runtime.coding.nativecline;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="chatchat.runtime.coding.temporal")
public class NativeTemporalProperties {
    private String target="127.0.0.1:7233",namespace="runtime-native",taskQueue="runtime-native-analysis-v1",nodeId="";
    private boolean workerEnabled;
    private int maxConcurrentTasks=1;
    private boolean tlsEnabled;
    private String tlsCertificateFile="",tlsKeyFile="",tlsCaFile="",apiKeyFile="";
    public boolean isTlsEnabled(){return tlsEnabled;}public void setTlsEnabled(boolean v){tlsEnabled=v;}
    public String getTlsCertificateFile(){return tlsCertificateFile;}public void setTlsCertificateFile(String v){tlsCertificateFile=v;}
    public String getTlsKeyFile(){return tlsKeyFile;}public void setTlsKeyFile(String v){tlsKeyFile=v;}
    public String getTlsCaFile(){return tlsCaFile;}public void setTlsCaFile(String v){tlsCaFile=v;}
    public String getApiKeyFile(){return apiKeyFile;}public void setApiKeyFile(String v){apiKeyFile=v;}
    public String getTarget(){return target;}public void setTarget(String v){target=v;}
    public String getNamespace(){return namespace;}public void setNamespace(String v){namespace=v;}
    public String getTaskQueue(){return taskQueue;}public void setTaskQueue(String v){taskQueue=v;}
    public String getNodeId(){return nodeId;}public void setNodeId(String v){nodeId=v;}
    public boolean isWorkerEnabled(){return workerEnabled;}public void setWorkerEnabled(boolean v){workerEnabled=v;}
    public int getMaxConcurrentTasks(){return maxConcurrentTasks;}
    public void setMaxConcurrentTasks(int v){if(v<1||v>64)throw new IllegalArgumentException("Bounded Native activity slots required");maxConcurrentTasks=v;}
}
