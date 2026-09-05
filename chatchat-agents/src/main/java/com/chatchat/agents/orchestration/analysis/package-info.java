/**
 * Analysis orchestration boundary.
 *
 * <p>Runtime graph operations live in {@code nodes.analysis}, {@code nodes.merge},
 * {@code nodes.synthesis} and {@code governance}. Supporting concerns live in {@code protocol}, {@code loop},
 * {@code semantic}, {@code prompt}, {@code checkpoint} and {@code logging}. Keeping this package
 * free of implementation classes prevents a generic summary package from becoming a catch-all.</p>
 */
package com.chatchat.agents.orchestration.analysis;
