/**
 * Framework-neutral contracts shared by the data-analysis Runtime.
 *
 * <ul>
 *   <li>{@code contract}: analytical methodology, reasoning depth, loop and role contracts.</li>
 *   <li>{@code model}: immutable assignments, scopes, positions, summaries and execution steps.</li>
 *   <li>{@code spi}: participant and model-assisted summary extension ports.</li>
 *   <li>{@code governance}: admission, lineage, lifecycle, repair and supervision policies.</li>
 *   <li>{@code semantic}: producer declarations and capability/evidence/claim semantics.</li>
 * </ul>
 *
 * <p>Models must not depend on extension ports. Concrete orchestration, model clients and report
 * rendering remain in implementation modules. Package organization does not change wire schema
 * versions or serialized field names.</p>
 */
package com.chatchat.common.runtime.summary.analysis;
