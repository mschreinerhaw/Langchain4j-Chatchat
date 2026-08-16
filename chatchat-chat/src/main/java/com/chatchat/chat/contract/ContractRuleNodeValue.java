package com.chatchat.chat.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class ContractRuleNodeValue {

    @Column(name = "rule_path", length = 512, nullable = false)
    private String rulePath;

    @Column(name = "parent_path", length = 512, nullable = false)
    private String parentPath;

    @Column(name = "rule_key", length = 256)
    private String ruleKey;

    @Column(name = "array_index")
    private Integer arrayIndex;

    @Column(name = "value_type", length = 16, nullable = false)
    private String valueType;

    @Column(name = "value_text", columnDefinition = "LONGTEXT")
    private String valueText;
}
