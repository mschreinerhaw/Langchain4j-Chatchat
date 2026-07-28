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
