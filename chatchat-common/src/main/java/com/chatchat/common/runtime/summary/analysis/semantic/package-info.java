/**
 * Source-neutral semantics connecting declared capabilities, returned evidence and analytical claims.
 *
 * <ul>
 *   <li>{@code model}: operations, producer declarations and capability/evidence/claim/gap data.</li>
 *   <li>{@code governance}: claim admission, revision lifecycle, gap derivation and resolution policies.</li>
 *   <li>{@code adapter}: producer declaration parsing and conversion into the analysis-loop protocol.</li>
 * </ul>
 *
 * <p>Models do not depend on governance or adapters. Governance uses declared semantics without
 * inventing business rules; adapters translate protocol representations without executing retrieval.</p>
 */
package com.chatchat.common.runtime.summary.analysis.semantic;
