package com.chatchat.common.runtime.summary.analysis;

/**
 * Evidence boundary owned by one analysis participant invocation.
 *
 * <p>Worker and Driver are deployment roles, not different analysis semantics. A participant
 * always summarizes only the evidence explicitly assigned to it; the scope describes how much
 * evidence the assignment contains.</p>
 */
public enum DataAnalysisScope {
    /** One complete dataset, normally handled by a parallel Worker. */
    DATASET,
    /** A relationship-authorized group of dataset summaries. */
    RELATED_DATASET_GROUP,
    /** Every dataset summary assigned to the final coordinating participant. */
    ASSIGNED_DATASET_COLLECTION
}
