package com.chatchat.mcpserver.metadata.search;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataRecord;
import com.chatchat.mcpserver.metadata.taxonomy.EnterpriseMetadataTaxonomyService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseMetadataScenarioClassifierTest {

    @Test
    void classifiesMetadataFromDatabaseTaxonomyAndBuildsStableScenarioVector() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();
        EnterpriseMetadataTaxonomyService taxonomyService = mock(EnterpriseMetadataTaxonomyService.class);
        when(taxonomyService.taxonomy()).thenReturn(taxonomy(
            new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
                "scenario-1", "customer_account", "客户与账户",
                "客户主体、账户开户和客户关系管理。", "customer", List.of(),
                10, false, List.of(
                    new EnterpriseMetadataTaxonomyService.TermDefinition("客户", "客户", 1.0D, "CONTAINS", 10),
                    new EnterpriseMetadataTaxonomyService.TermDefinition("账户", "账户", 0.9D, "CONTAINS", 20)
                )
            )
        ));
        EnterpriseMetadataScenarioClassifier classifier =
            new EnterpriseMetadataScenarioClassifier(properties, taxonomyService);
        EnterpriseMetadataVectorizer vectorizer = new EnterpriseMetadataVectorizer(properties);
        EnterpriseMetadataRecord classified = classifier.classify(new EnterpriseMetadataRecord(
            "F001", "metadata_field", "enterprise_field_catalog",
            "客户账户编码", "CUST_ACCT_NUM", "客户账户唯一编码",
            "标准", "fields.xlsx#fields", Map.of("dataType", "字符型")
        ));

        assertThat(classified.attributes())
            .containsEntry("scenarioCodes", List.of("customer_account"))
            .containsKey("scenarioDescription")
            .containsKey("semanticText");
        List<Float> first = vectorizer.vectorize(String.valueOf(classified.attributes().get("semanticText")));
        List<Float> second = vectorizer.vectorize(String.valueOf(classified.attributes().get("semanticText")));
        assertThat(first).hasSize(256).isEqualTo(second);
        double norm = Math.sqrt(first.stream().mapToDouble(value -> value * value).sum());
        assertThat(norm).isCloseTo(1.0D, org.assertj.core.data.Offset.offset(0.0001D));
    }

    @Test
    void appliesDatabaseTermWeightsWhenSelectingScenarios() {
        EnterpriseMetadataProperties properties = new EnterpriseMetadataProperties();
        properties.getScenarioClassification().setMaxScenariosPerRecord(1);
        EnterpriseMetadataTaxonomyService taxonomyService = mock(EnterpriseMetadataTaxonomyService.class);
        EnterpriseMetadataTaxonomyService.ScenarioDefinition customer = new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
            "customer", "customer_account", "客户与账户", "客户管理", "customer",
            List.of(), 20, false, List.of(
                new EnterpriseMetadataTaxonomyService.TermDefinition("客户", "客户", 0.5D, "CONTAINS", 10)
            )
        );
        EnterpriseMetadataTaxonomyService.ScenarioDefinition risk = new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
            "risk", "risk_compliance", "风险与合规", "风险管理", "risk",
            List.of(), 30, false, List.of(
                new EnterpriseMetadataTaxonomyService.TermDefinition("风险客户", "风险客户", 2.0D, "CONTAINS", 10)
            )
        );
        when(taxonomyService.taxonomy()).thenReturn(taxonomy(customer, risk));
        EnterpriseMetadataScenarioClassifier classifier =
            new EnterpriseMetadataScenarioClassifier(properties, taxonomyService);

        assertThat(classifier.classifyQuery("风险客户名单")).containsExactly("risk_compliance");
    }

    private EnterpriseMetadataTaxonomyService.TaxonomySnapshot taxonomy(
        EnterpriseMetadataTaxonomyService.ScenarioDefinition... scenarios) {
        return new EnterpriseMetadataTaxonomyService.TaxonomySnapshot(
            List.of(scenarios),
            new EnterpriseMetadataTaxonomyService.ScenarioDefinition(
                "fallback", "general_metadata", "通用数据标准", "通用企业数据标准",
                "common", List.of(), 999, true, List.of()
            )
        );
    }
}
