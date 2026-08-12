package com.chatchat.chat.uiartifact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.ui-artifact")
public class UiArtifactProperties {

    private boolean enabled = true;
    private String storeType = "local";
    private String storagePath = "./data/ui-artifacts";
    private String sharedMarkerFile = ".chatchat-artifact-store";
    private boolean migrateLegacyOnRead = true;
    private boolean alwaysExternalize = true;
    private int externalizeThresholdBytes = 131_072;
    private int answerPreviewCharacters = 4_000;
    private long ttlSeconds = 2_592_000;
    private long cleanupIntervalMs = 3_600_000;
}
