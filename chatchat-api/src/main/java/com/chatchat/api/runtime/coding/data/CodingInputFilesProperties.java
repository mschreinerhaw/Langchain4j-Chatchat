package com.chatchat.api.runtime.coding.data;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Data @Component @ConfigurationProperties(prefix="chatchat.coding.input-files")
public class CodingInputFilesProperties {
    private boolean enabled;
    private String sharedRoot;
    private CodingInputManifest.Format format=CodingInputManifest.Format.PARQUET;
    private int partRows=10000;
}
