package com.chatchat.mcpserver.metadata;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class MetadataGovernancePolicy {

    private String version;
    private MetadataContract metadataContract = new MetadataContract();
    private ClaimCoverage claimCoverage = new ClaimCoverage();
    private SearchPolicy search = new SearchPolicy();
    private ComparisonPolicy comparison = new ComparisonPolicy();

    @Data
    public static class MetadataContract {
        private String fieldType;
        private String termType;
        private String dictionaryType;
        private List<String> requiredBundle = new ArrayList<>();
        private Map<String, String> typeAliases = new LinkedHashMap<>();
        private String dataTypeAttribute;
        private String lengthAttribute;
        private String precisionAttribute;
        private String nullableAttribute;
        private String valueRangeAttribute;
        private String englishNameAttribute;
        private String abbreviationAttribute;
        private String dictionaryEnglishNameAttribute;
        private String dictionaryIdAttribute;
    }

    @Data
    public static class ClaimCoverage {
        private String contractVersion = "enterprise_metadata_claim_coverage.v1";
        private String scope = "ENTERPRISE_FIELD_METADATA";
        private List<String> supportedClaims = new ArrayList<>(List.of(
            "standard field name and definition alignment",
            "standard field data-type alignment when the returned record declares a type",
            "business term/root alignment",
            "code dictionary alignment"
        ));
        private List<String> notAssessedClaims = new ArrayList<>(List.of(
            "primary or unique key design",
            "partitioning, bucketing, or indexing design",
            "storage format or compression",
            "retention, lifecycle, or TTL policy",
            "complete table-level enterprise design conformance"
        ));
        private boolean fullTableDesignConformanceSupported;
        private String interpretation =
            "success=true means retrieval completed, not that every requested governance claim is supported";
    }

    @Data
    public static class SearchPolicy {
        private double exactWeight;
        private double containsWeight;
        private double tokenWeight;
        private double preferredStatusWeight;
        private List<String> preferredStatuses = new ArrayList<>();
        private int termExpansionLimit;
        private double confidenceBase;
        private double confidenceSlope;
        private double confidenceMaximum;
    }

    @Data
    public static class ComparisonPolicy {
        private double minimumFieldScore;
        private double exactNameScore;
        private double exactCommentScore;
        private double partialCommentScore;
        private double dictionaryContextScore;
        private double unmatchedTokenPenalty;
        private double maximumUnmatchedPenalty;
        private int maximumDictionaryMatches;
        private Map<String, String> differenceSeverities = new LinkedHashMap<>();
        private Map<String, String> differenceMessages = new LinkedHashMap<>();
        private List<String> nullableTrueValues = new ArrayList<>();
        private List<String> nullableFalseValues = new ArrayList<>();
    }
}
