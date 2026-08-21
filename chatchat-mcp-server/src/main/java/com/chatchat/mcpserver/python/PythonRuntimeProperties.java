package com.chatchat.mcpserver.python;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter @Component @ConfigurationProperties(prefix="chatchat.mcp.python")
public class PythonRuntimeProperties {
    private String workspaceRoot="./data/python-assets";
    private String dockerCommand="docker";
    private int outputLimitBytes=1_000_000;
    private String dataRoot="./data/python-user-data";
    private long maxDataFileBytes=50L*1024*1024;
}
