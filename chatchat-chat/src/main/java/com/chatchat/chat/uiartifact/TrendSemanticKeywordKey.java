package com.chatchat.chat.uiartifact;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
public class TrendSemanticKeywordKey implements Serializable {

    private String tenantId;
    private String keyword;

    public TrendSemanticKeywordKey(String tenantId, String keyword) {
        this.tenantId = tenantId;
        this.keyword = keyword;
    }
}
