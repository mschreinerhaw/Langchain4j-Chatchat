package com.chatchat.api.datascience;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter @Component
@ConfigurationProperties(prefix="chatchat.data-science")
public class PythonDataScienceProperties {
    private String workspaceRoot="./data/python-assets";
    private String dockerCommand="docker";
    private String defaultImage="python:3.12-slim";
    private String cpuLimit="2";
    private String memoryLimit="4g";
    private int timeoutSeconds=300;
    private int outputLimitBytes=1_000_000;
    private String indexName="mcp_python_template_index";
}
