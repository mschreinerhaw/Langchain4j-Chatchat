package com.chatchat.mcpserver.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EnterpriseMetadataTestProperties {

    private EnterpriseMetadataTestProperties() {
    }

    static MetadataGovernancePolicyService policyService() {
        MetadataGovernancePolicyService service = mock(MetadataGovernancePolicyService.class);
        MetadataGovernancePolicy policy = policy();
        when(service.current()).thenReturn(policy);
        when(service.evidenceCoverage()).thenReturn(MetadataGovernancePolicyService.evidenceCoverage(
            policy.getEvidenceCoverage(), policy.getVersion()));
        return service;
    }

    static MetadataGovernancePolicy policy() {
        MetadataGovernancePolicy policy = new MetadataGovernancePolicy();
        policy.setVersion("test-policy-v1");
        MetadataGovernancePolicy.MetadataContract contract = policy.getMetadataContract();
        contract.setFieldType("metadata_field");
        contract.setTermType("metadata_term");
        contract.setDictionaryType("metadata_dictionary");
        contract.setRequiredBundle(List.of(
            "metadata_field", "metadata_term", "metadata_dictionary"));
        contract.setTypeAliases(Map.of(
            "field", "metadata_field",
            "standard_field", "metadata_field",
            "term", "metadata_term",
            "root", "metadata_term",
            "root_word", "metadata_term",
            "dictionary", "metadata_dictionary",
            "code_dictionary", "metadata_dictionary"
        ));
        contract.setDataTypeAttribute("dataType");
        contract.setLengthAttribute("length");
        contract.setPrecisionAttribute("precision");
        contract.setNullableAttribute("nullable");
        contract.setValueRangeAttribute("valueRange");
        contract.setEnglishNameAttribute("englishName");
        contract.setAbbreviationAttribute("abbreviation");
        contract.setDictionaryEnglishNameAttribute("dictionaryEnglishName");
        contract.setDictionaryIdAttribute("dictionaryId");

        MetadataGovernancePolicy.SearchPolicy search = policy.getSearch();
        search.setExactWeight(1.0D);
        search.setContainsWeight(0.75D);
        search.setTokenWeight(0.25D);
        search.setPreferredStatusWeight(0.10D);
        search.setPreferredStatuses(List.of("active", "标准"));
        search.setTermExpansionLimit(30);
        search.setConfidenceBase(0.55D);
        search.setConfidenceSlope(0.15D);
        search.setConfidenceMaximum(0.99D);

        MetadataGovernancePolicy.ComparisonPolicy comparison = policy.getComparison();
        comparison.setMinimumFieldScore(0.55D);
        comparison.setExactNameScore(1.0D);
        comparison.setExactCommentScore(0.9D);
        comparison.setPartialCommentScore(0.75D);
        comparison.setDictionaryContextScore(0.8D);
        comparison.setUnmatchedTokenPenalty(0.05D);
        comparison.setMaximumUnmatchedPenalty(0.25D);
        comparison.setMaximumDictionaryMatches(10);
        comparison.setNullableTrueValues(List.of(
            "y", "yes", "true", "1", "nullable", "是", "可空"));
        comparison.setNullableFalseValues(List.of(
            "n", "no", "false", "0", "notnull", "否", "非空"));
        Map<String, String> severities = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        rule(severities, messages, "STANDARD_FIELD_MISSING", "ERROR", "字段未匹配到维护的标准字段");
        rule(severities, messages, "TECHNICAL_NAME_MISMATCH", "WARNING", "技术名称不一致");
        rule(severities, messages, "DATA_TYPE_MISMATCH", "ERROR", "数据类型不一致");
        rule(severities, messages, "NULLABILITY_MISMATCH", "ERROR", "可空约束不一致");
        rule(severities, messages, "TERM_NOT_STANDARD", "WARNING", "词根未标准化");
        rule(severities, messages, "DICTIONARY_MAPPING_MISSING", "WARNING", "字典映射缺失");
        comparison.setDifferenceSeverities(severities);
        comparison.setDifferenceMessages(messages);
        return policy;
    }

    private static void rule(Map<String, String> severities, Map<String, String> messages,
                             String code, String severity, String message) {
        severities.put(code, severity);
        messages.put(code, message);
    }
}
