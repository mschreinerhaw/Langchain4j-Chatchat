package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class MetadataGovernancePolicy {

    private String version;
    private MetadataContract metadataContract = new MetadataContract();
    @JsonAlias("claimCoverage")
    private EvidenceCoverage evidenceCoverage = new EvidenceCoverage();
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvidenceCoverage {
        private String contractVersion = "enterprise_metadata_evidence_coverage.v2";
        private String scope = "ENTERPRISE_FIELD_METADATA";
        private String evidenceRole = "STANDARD_REFERENCE_DATA";
        private List<String> returnedEvidenceTypes = new ArrayList<>(List.of(
            "standard field metadata",
            "business term and root metadata",
            "code dictionary metadata"
        ));
        private String interpretation =
            "Describes enterprise field metadata returned as standard reference data; conclusions are produced by the model from all available evidence";
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
        private double minimumQualityScore = 0.38D;
        private double minimumSelectionMargin = 0.03D;
        private double lexicalQualityWeight = 0.60D;
        private double retrievalQualityWeight = 0.30D;
        private double statusQualityWeight = 0.10D;
        private int candidateExpansionFactor = 6;
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
