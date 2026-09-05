/**
 * Stable cross-layer contracts of the Agent Runtime OS.
 *
 * <p>The package owns Agent-specific ports and immutable protocol models only. The generic marker
 * and registry live in {@code com.chatchat.common.runtime.protocol}. Orchestration and tool code must
 * depend on these interfaces rather than concrete bridges. Default implementations are composed
 * by {@link com.chatchat.agents.orchestration.protocol.RuntimeProtocolConfiguration}; extensions add a
 * new adapter/port implementation without modifying the Runtime scheduler or final synthesizer.</p>
 *
 * <p>The analysis pipeline is layered as follows:</p>
 * <ol>
 *   <li>{@link com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol} establishes the
 *       authoritative evidence and isolation boundary.</li>
 *   <li>{@link com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol} selects a
 *       {@link com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter} and emits
 *       canonical datasets.</li>
 *   <li>{@link com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol} supplies
 *       source-neutral semantic and governance context.</li>
 *   <li>{@link com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol} governs chunk
 *       analysis, preservation, lineage, and final synthesis products from the common layer.</li>
 * </ol>
 *
 * <p>Protocol implementations must not select on business tool names, mutate authoritative
 * payloads, silently truncate evidence, or allow returned data to install executable policy.</p>
 */
package com.chatchat.agents.runtime.protocol;
