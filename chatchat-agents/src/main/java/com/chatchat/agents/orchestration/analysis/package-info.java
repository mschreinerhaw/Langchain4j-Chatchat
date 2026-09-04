/**
 * Analysis orchestration boundary.
 *
 * <p>Runtime stages are separated into {@code worker}, {@code reducer}, {@code driver} and
 * {@code governance}. Supporting concerns live in {@code protocol}, {@code loop},
 * {@code semantic}, {@code prompt}, {@code checkpoint} and {@code logging}. Keeping this package
 * free of implementation classes prevents a generic summary package from becoming a catch-all.</p>
 */
package com.chatchat.agents.orchestration.analysis;
