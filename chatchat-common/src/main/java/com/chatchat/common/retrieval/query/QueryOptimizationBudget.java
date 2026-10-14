package com.chatchat.common.retrieval.query;

import java.util.List;

/** Central application defaults, not OpenSearch limits. Tune profiles against a representative corpus. */
public class QueryOptimizationBudget {
    public static final int DEFAULT_TOKEN_CHAR_BUDGET = 64;
    private int longTextThreshold = 256;
    private int maxInputBytes = 262144;
    private int maxKeywords = 24;
    private int maxBatches = 16;
    private int maxCandidates = 500;
    private int maxTotalCandidates = 4000;
    private int maxConcurrent = 2;
    private int requestTimeoutMs = 8000;
    private int totalTimeoutMs = 20000;
    private int maxDslDepth = 24;
    private int maxDslNodes = 4096;
    private int maxTermsValues = 20000;
    private int maxEstimatedClauses = 512;
    private int maxRequestBytes = 1048576;
    private int maxFields = 32;
    private int maxAggregationBuckets = 10000;
    private int maxOffset = 10000;
    private int rrfRankConstant = 60;
    private int clauseHeadroom = 32;
    private int maxKeywordCodePoints = DEFAULT_TOKEN_CHAR_BUDGET;
    private int analyzerExpansionFactor = 4;
    private int maxRefinementCalls = 1;
    private boolean modelRefinementEnabled;
    private String refinementModel = "";
    private int refinementTimeoutMs = 3000;
    private int refinementMaxOutputTokens = 512;
    private List<String> protectedTerms = List.of();

    public int getLongTextThreshold() { return longTextThreshold; }
    public int getAnalyzerExpansionFactor() { return analyzerExpansionFactor; }
    public void setAnalyzerExpansionFactor(int value) { analyzerExpansionFactor = positive(value); }
    public int getMaxRefinementCalls() { return maxRefinementCalls; }
    public void setMaxRefinementCalls(int value) { maxRefinementCalls = positive(value); }
    public int getClauseHeadroom() { return clauseHeadroom; }
    public void setClauseHeadroom(int value) { clauseHeadroom = positive(value); }
    public int getMaxKeywordCodePoints() { return maxKeywordCodePoints; }
    public void setMaxKeywordCodePoints(int value) { maxKeywordCodePoints = positive(value); }
    public int getMaxFields() { return maxFields; }
    public void setMaxFields(int value) { maxFields = positive(value); }
    public int getMaxAggregationBuckets() { return maxAggregationBuckets; }
    public void setMaxAggregationBuckets(int value) { maxAggregationBuckets = positive(value); }
    public int getMaxOffset() { return maxOffset; }
    public void setMaxOffset(int value) { maxOffset = positive(value); }
    public int getRrfRankConstant() { return rrfRankConstant; }
    public void setRrfRankConstant(int value) { rrfRankConstant = positive(value); }
    public void setLongTextThreshold(int value) { longTextThreshold = positive(value); }
    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int value) { maxInputBytes = positive(value); }
    public int getMaxKeywords() { return maxKeywords; }
    public void setMaxKeywords(int value) { maxKeywords = positive(value); }
    public int getMaxBatches() { return maxBatches; }
    public void setMaxBatches(int value) { maxBatches = positive(value); }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int value) { maxCandidates = positive(value); }
    public int getMaxTotalCandidates() { return maxTotalCandidates; }
    public void setMaxTotalCandidates(int value) { maxTotalCandidates = positive(value); }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int value) { maxConcurrent = positive(value); }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int value) { requestTimeoutMs = positive(value); }
    public int getTotalTimeoutMs() { return totalTimeoutMs; }
    public void setTotalTimeoutMs(int value) { totalTimeoutMs = positive(value); }
    public int getMaxDslDepth() { return maxDslDepth; }
    public void setMaxDslDepth(int value) { maxDslDepth = positive(value); }
    public int getMaxDslNodes() { return maxDslNodes; }
    public void setMaxDslNodes(int value) { maxDslNodes = positive(value); }
    public int getMaxTermsValues() { return maxTermsValues; }
    public void setMaxTermsValues(int value) { maxTermsValues = positive(value); }
    public int getMaxEstimatedClauses() { return maxEstimatedClauses; }
    public void setMaxEstimatedClauses(int value) { maxEstimatedClauses = positive(value); }
    public int getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(int value) { maxRequestBytes = positive(value); }
    public boolean isModelRefinementEnabled() { return modelRefinementEnabled; }
    public void setModelRefinementEnabled(boolean value) { modelRefinementEnabled = value; }
    public String getRefinementModel() { return refinementModel; }
    public void setRefinementModel(String value) { refinementModel = value; }
    public int getRefinementTimeoutMs() { return refinementTimeoutMs; }
    public int getRefinementMaxOutputTokens() { return refinementMaxOutputTokens; }
    public void setRefinementMaxOutputTokens(int value) { refinementMaxOutputTokens = positive(value); }
    public void setRefinementTimeoutMs(int value) { refinementTimeoutMs = positive(value); }
    public List<String> getProtectedTerms() { return protectedTerms; }
    public void setProtectedTerms(List<String> value) { protectedTerms = value == null ? List.of() : List.copyOf(value); }
    private static int positive(int value) {
        if (value < 1) throw new IllegalArgumentException("Query budget values must be positive");
        return value;
    }
}
