/**
 * Runtime OS contracts for governed model summarization and distributed map-reduce execution.
 * {@link com.chatchat.common.runtime.summary.DataAnalysisLifecycle} enforces the mandatory
 * relationship, dispatch, reconciliation and final-synthesis sequence.
 * {@link com.chatchat.common.runtime.summary.DataAnalysisSummaryProtocol} is the stable
 * model-assisted data-analysis boundary; upper modules provide governance implementations.
 * This package deliberately has no model SDK, Spring, persistence or transport dependency.
 */
package com.chatchat.common.runtime.summary;
