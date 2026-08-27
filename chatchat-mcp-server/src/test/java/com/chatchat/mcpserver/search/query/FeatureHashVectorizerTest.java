package com.chatchat.mcpserver.search.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureHashVectorizerTest {

    @Test
    void includesBusinessDescriptionsAndWorkflowStepTextInStableVector() {
        String semanticText = "客户资产一致性核验 数据核验 检查不同系统资产余额差异 "
            + "步骤一 查询客户资产 步骤二 对比余额并输出差异";
        List<Float> first = FeatureHashVectorizer.vectorize(semanticText, 256);
        List<Float> second = FeatureHashVectorizer.vectorize(semanticText, 256);

        assertThat(first).hasSize(256).isEqualTo(second);
        double norm = Math.sqrt(first.stream().mapToDouble(value -> value * value).sum());
        assertThat(norm).isCloseTo(1.0D, org.assertj.core.data.Offset.offset(0.0001D));
    }
}
