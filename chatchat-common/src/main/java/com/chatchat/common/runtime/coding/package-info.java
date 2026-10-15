/**
 * Framework-neutral Runtime OS Coding v1 contracts and pure validation/hashing algorithms.
 *
 * <p>Transport adapters must validate JSON against {@code runtime/coding/coding.schema.json}
 * before mapping these records, reject unknown enums, and then apply constructor and successor
 * validation. Schema checks alone cannot compare timestamps, task identities or revisions.
 * Scope is supplied by authenticated Runtime code, never trusted from a model or provider.
 *
 * <p>No implementation here starts processes, persists tasks, enforces budgets, authorizes
 * resource access or publishes reports. Those responsibilities remain with Runtime adapters.
 */
package com.chatchat.common.runtime.coding;
