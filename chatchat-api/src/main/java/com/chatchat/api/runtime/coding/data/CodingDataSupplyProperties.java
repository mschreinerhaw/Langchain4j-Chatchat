package com.chatchat.api.runtime.coding.data;

import java.util.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit deployment grants. Model text cannot create or widen a grant. */
@Data @Component @ConfigurationProperties(prefix="chatchat.coding.analysis.data-supply")
public class CodingDataSupplyProperties {
    private boolean enabled;
    private int maxRounds = 4;
    private List<Grant> grants = List.of();
    public record Grant(String tenantId, String userId, String skillId, String templateId,
        String assetName, String environment, Map<String,List<String>> parameterValues, List<String> requiredFields) { }
}
