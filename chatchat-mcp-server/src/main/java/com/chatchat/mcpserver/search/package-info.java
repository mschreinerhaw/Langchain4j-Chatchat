/**
 * MCP search infrastructure, separated by transport, engine, indexing, and query concerns.
 *
 * <p>Dependencies should flow from {@code admin} and {@code index} toward {@code engine}
 * and {@code query}. Query utilities must remain independent of Spring and storage backends.</p>
 */
package com.chatchat.mcpserver.search;
